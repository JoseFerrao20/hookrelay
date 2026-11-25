#!/usr/bin/env bash
# End-to-end smoke test against a running hookrelay stack (see docs/contracts/E1.md §6).
# Usage: scripts/smoke-test.sh [BASE_URL] [SINK_URL]
#   defaults: http://localhost:8080 http://localhost:8090 (see docs/contracts/E2.md §9)
# Env: HOOKRELAY_API_KEY (default: the dev-only key from docker-compose.yml / .env.example)
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
SINK_URL="${2:-http://localhost:8090}"
SINK_URL="${SINK_URL%/}"
API="$BASE_URL/api/v1"
# DEV ONLY default, same as the compose default. Override for any real deployment.
API_KEY="${HOOKRELAY_API_KEY:-dev-only-api-key-change-me-0123456789}"

for tool in curl jq openssl; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required tool '$tool' is not installed or not on PATH" >&2
    exit 2
  fi
done

STEP="init"
ENDPOINT_ID=""
EXTRA_ENDPOINT_IDS=""
HTTP_STATUS=""
BODY_FILE="$(mktemp)"
HEADERS_FILE="$(mktemp)"

cleanup() {
  local rc=$?
  # Best-effort removal of the endpoint if we fail before the delete step.
  if [ "$rc" -ne 0 ] && [ -n "$ENDPOINT_ID" ]; then
    curl -s -o /dev/null -H "Authorization: Bearer $API_KEY" -X DELETE "$API/endpoints/$ENDPOINT_ID" || true
  fi
  if [ "$rc" -ne 0 ]; then
    for id in $EXTRA_ENDPOINT_IDS; do
      curl -s -o /dev/null -H "Authorization: Bearer $API_KEY" -X DELETE "$API/endpoints/$id" || true
    done
  fi
  rm -f "$BODY_FILE" "$HEADERS_FILE"
  exit "$rc"
}
trap cleanup EXIT

step() {
  STEP="$1"
  echo "==> $STEP"
}

fail() {
  echo "SMOKE TEST FAILED at step: $STEP" >&2
  echo "  $*" >&2
  if [ -s "$BODY_FILE" ]; then
    echo "  last response body: $(head -c 500 "$BODY_FILE")" >&2
  fi
  exit 1
}

# request METHOD URL [curl args...] -> sets HTTP_STATUS, body in $BODY_FILE
request() {
  local method="$1" url="$2"
  shift 2
  : >"$BODY_FILE"
  HTTP_STATUS="$(curl -sS -o "$BODY_FILE" -D "$HEADERS_FILE" -w '%{http_code}' \
    -H "Authorization: Bearer $API_KEY" -X "$method" "$url" "$@")" || fail "request failed: $method $url"
}

expect_status() {
  [ "$HTTP_STATUS" = "$1" ] || fail "expected HTTP $1 but got $HTTP_STATUS"
}

# sink_record DELIVERY_ID -> prints (compact JSON) the first request the sink recorded for that delivery
sink_record() {
  request GET "$SINK_URL/received"
  expect_status 200
  jq -c --arg id "$1" \
    '[.[] | select((.headers | to_entries | map(select(.key | ascii_downcase == "hookrelay-delivery-id")) | .[0].value) == $id)] | .[0] // empty' \
    "$BODY_FILE"
}

# rec_header RECORD NAME -> value of a header (case-insensitive)
rec_header() {
  jq -r --arg n "$2" '.headers | to_entries | map(select(.key | ascii_downcase == $n)) | .[0].value // empty' <<<"$1"
}

# expected_sig SECRET DELIVERY_ID TIMESTAMP RECORD
# -> base64(HMAC-SHA256(key = full secret incl. "whsec_", msg = "<deliveryId>.<timestamp>.<body>")),
#    where <body> is the exact raw bytes the sink recorded (body_b64).
expected_sig() {
  local bodyfile
  bodyfile="$(mktemp)"
  jq -r '.body_b64' <<<"$4" | openssl base64 -d -A >"$bodyfile"
  { printf '%s.%s.' "$2" "$3"; cat "$bodyfile"; } \
    | openssl dgst -sha256 -hmac "$1" -binary | openssl base64 -A
  rm -f "$bodyfile"
}

# wait_succeeded DELIVERY_ID SECONDS
wait_succeeded() {
  local deadline=$((SECONDS + $2)) st
  while :; do
    request GET "$API/deliveries/$1"
    expect_status 200
    st="$(jq -r '.status // empty' "$BODY_FILE")"
    [ "$st" != "SUCCEEDED" ] || return 0
    [ "$SECONDS" -lt "$deadline" ] || fail "delivery $1 still '$st' after $2s"
    sleep 1
  done
}

step "1. wait for $BASE_URL/actuator/health to report UP (max 60s)"
deadline=$((SECONDS + 60))
until curl -s --max-time 5 "$BASE_URL/actuator/health" 2>/dev/null | jq -e '.status == "UP"' >/dev/null 2>&1; do
  [ "$SECONDS" -lt "$deadline" ] || fail "application did not become healthy within 60s"
  sleep 2
done

step "2. API without a key is rejected (401)"
NOAUTH_STATUS="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' "$API/endpoints")" || fail "request failed: GET $API/endpoints"
[ "$NOAUTH_STATUS" = "401" ] || fail "expected HTTP 401 without an API key but got $NOAUTH_STATUS"

step "3. create endpoint"
request POST "$API/endpoints" -H 'Content-Type: application/json' \
  -d '{"url":"http://webhook-sink:8080/hooks","description":"smoke test","eventTypes":["smoke.test"]}'
expect_status 201
ENDPOINT_ID="$(jq -r '.id // empty' "$BODY_FILE")"
[ -n "$ENDPOINT_ID" ] || fail "response has no id"
jq -e '.secret | type == "string" and length > 0' "$BODY_FILE" >/dev/null \
  || fail "response has no secret"
SECRET="$(jq -r '.secret' "$BODY_FILE")"

step "4. post event with Idempotency-Key"
KEY="smoke-$(date +%s)-$RANDOM-$RANDOM"
EVENT='{"eventType":"smoke.test","payload":{"smoke":true}}'
request POST "$API/events" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$EVENT"
expect_status 202
EVENT_ID="$(jq -r '.id // empty' "$BODY_FILE")"
[ -n "$EVENT_ID" ] || fail "event response has no id"
jq -e --arg ep "$ENDPOINT_ID" \
  '[.deliveries[] | select(.endpointId == $ep)] | length == 1 and .[0].status == "PENDING"' \
  "$BODY_FILE" >/dev/null || fail "expected exactly one PENDING delivery for the endpoint"
DELIVERY_ID="$(jq -r --arg ep "$ENDPOINT_ID" '[.deliveries[] | select(.endpointId == $ep)][0].id // empty' "$BODY_FILE")"
[ -n "$DELIVERY_ID" ] || fail "delivery has no id"

step "5. replay same request, expect 200 and same event id"
request POST "$API/events" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$EVENT"
expect_status 200
REPLAY_ID="$(jq -r '.id // empty' "$BODY_FILE")"
[ "$REPLAY_ID" = "$EVENT_ID" ] || fail "replay returned event id '$REPLAY_ID', expected '$EVENT_ID'"

step "6. read delivery by eventId"
request GET "$API/deliveries?eventId=$EVENT_ID"
expect_status 200
jq -e --arg ep "$ENDPOINT_ID" \
  '[.content[] | select(.endpointId == $ep)] | length == 1' \
  "$BODY_FILE" >/dev/null || fail "delivery for the endpoint not found"

step "7. wait for delivery $DELIVERY_ID to be SUCCEEDED (max 30s)"
deadline=$((SECONDS + 30))
while :; do
  request GET "$API/deliveries/$DELIVERY_ID"
  expect_status 200
  DSTATUS="$(jq -r '.status // empty' "$BODY_FILE")"
  [ "$DSTATUS" != "SUCCEEDED" ] || break
  [ "$SECONDS" -lt "$deadline" ] || fail "delivery still '$DSTATUS' after 30s"
  sleep 1
done

step "8. delivery has exactly one SUCCEEDED attempt"
request GET "$API/deliveries/$DELIVERY_ID/attempts"
expect_status 200
jq -e 'length == 1 and .[0].outcome == "SUCCEEDED"' "$BODY_FILE" >/dev/null \
  || fail "expected 1 attempt with outcome SUCCEEDED"

step "9. sink received the webhook with the right header and body"
request GET "$SINK_URL/received"
expect_status 200
jq -e --arg id "$DELIVERY_ID" --argjson payload '{"smoke":true}' \
  '[.[] | select(((.headers | to_entries | map(select(.key | ascii_downcase == "hookrelay-delivery-id")) | .[0].value) == $id)
                 and ((.body | fromjson? // null) == $payload))] | length >= 1' \
  "$BODY_FILE" >/dev/null || fail "sink has no matching request for delivery $DELIVERY_ID"

step "10. webhook signature verifies (openssl, exact body recorded by the sink)"
SINK_REC="$(sink_record "$DELIVERY_ID")"
[ -n "$SINK_REC" ] || fail "sink has no request for delivery $DELIVERY_ID"
jq -e 'has("body_b64")' <<<"$SINK_REC" >/dev/null || fail "sink record has no body_b64 (stale sink?)"
TS="$(rec_header "$SINK_REC" hookrelay-timestamp)"
SIG_HEADER="$(rec_header "$SINK_REC" hookrelay-signature)"
[ -n "$TS" ] || fail "webhook has no Hookrelay-Timestamp header"
[ -n "$SIG_HEADER" ] || fail "webhook has no Hookrelay-Signature header"
EXPECTED="$(expected_sig "$SECRET" "$DELIVERY_ID" "$TS" "$SINK_REC")"
[ -n "$EXPECTED" ] || fail "could not compute the expected signature"
case " $SIG_HEADER " in
  *" v1=$EXPECTED "*) ;;
  *) fail "Hookrelay-Signature '$SIG_HEADER' does not contain expected v1=$EXPECTED" ;;
esac

step "11. rotate secret: next webhook carries two signatures (new and old secret)"
request POST "$API/endpoints/$ENDPOINT_ID/secret/rotate"
expect_status 200
NEW_SECRET="$(jq -r '.secret // empty' "$BODY_FILE")"
[ -n "$NEW_SECRET" ] || fail "rotate response has no secret"
[ "$NEW_SECRET" != "$SECRET" ] || fail "rotation returned the same secret"
jq -e '.previousSecretExpiresAt | type == "string"' "$BODY_FILE" >/dev/null \
  || fail "rotate response has no previousSecretExpiresAt"
request POST "$API/events" -H 'Content-Type: application/json' \
  -d '{"eventType":"smoke.test","payload":{"smoke":"rotated"}}'
expect_status 202
ROT_DELIVERY_ID="$(jq -r --arg ep "$ENDPOINT_ID" '[.deliveries[] | select(.endpointId == $ep)][0].id // empty' "$BODY_FILE")"
[ -n "$ROT_DELIVERY_ID" ] || fail "no delivery for the rotated endpoint"
wait_succeeded "$ROT_DELIVERY_ID" 30
ROT_REC="$(sink_record "$ROT_DELIVERY_ID")"
[ -n "$ROT_REC" ] || fail "sink has no request for delivery $ROT_DELIVERY_ID"
ROT_TS="$(rec_header "$ROT_REC" hookrelay-timestamp)"
ROT_HEADER="$(rec_header "$ROT_REC" hookrelay-signature)"
[ -n "$ROT_HEADER" ] || fail "rotated webhook has no Hookrelay-Signature header"
# shellcheck disable=SC2086
set -- $ROT_HEADER
[ "$#" -eq 2 ] || fail "expected two signature entries, got $#: '$ROT_HEADER'"
EXP_NEW="v1=$(expected_sig "$NEW_SECRET" "$ROT_DELIVERY_ID" "$ROT_TS" "$ROT_REC")"
EXP_OLD="v1=$(expected_sig "$SECRET" "$ROT_DELIVERY_ID" "$ROT_TS" "$ROT_REC")"
[ "$1" = "$EXP_NEW" ] || fail "first signature '$1' does not match the new secret ($EXP_NEW)"
[ "$2" = "$EXP_OLD" ] || fail "second signature '$2' does not match the old secret ($EXP_OLD)"

step "12. flaky endpoint (fails twice): SUCCEEDED on attempt 3 (max 60s)"
FLAKY_KEY="smoke-$(date +%s)-$RANDOM"
request POST "$API/endpoints" -H 'Content-Type: application/json' \
  -d "{\"url\":\"http://webhook-sink:8080/flaky/$FLAKY_KEY?fail=2\",\"description\":\"smoke flaky\",\"eventTypes\":[\"smoke.flaky\"]}"
expect_status 201
FLAKY_ENDPOINT_ID="$(jq -r '.id // empty' "$BODY_FILE")"
[ -n "$FLAKY_ENDPOINT_ID" ] || fail "flaky endpoint response has no id"
EXTRA_ENDPOINT_IDS="$EXTRA_ENDPOINT_IDS $FLAKY_ENDPOINT_ID"
request POST "$API/events" -H 'Content-Type: application/json' \
  -d '{"eventType":"smoke.flaky","payload":{"smoke":"flaky"}}'
expect_status 202
FLAKY_DELIVERY_ID="$(jq -r --arg ep "$FLAKY_ENDPOINT_ID" '[.deliveries[] | select(.endpointId == $ep)][0].id // empty' "$BODY_FILE")"
[ -n "$FLAKY_DELIVERY_ID" ] || fail "no delivery for the flaky endpoint"
deadline=$((SECONDS + 60))
while :; do
  request GET "$API/deliveries/$FLAKY_DELIVERY_ID"
  expect_status 200
  DSTATUS="$(jq -r '.status // empty' "$BODY_FILE")"
  [ "$DSTATUS" != "SUCCEEDED" ] || break
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "  hint: the stack must run with fast retries (make up-all, or HOOKRELAY_RETRY_INITIAL_DELAY=1s HOOKRELAY_RETRY_MAX_DELAY=5s HOOKRELAY_RETRY_JITTER=0)" >&2
    fail "flaky delivery still '$DSTATUS' after 60s"
  fi
  sleep 1
done
request GET "$API/deliveries/$FLAKY_DELIVERY_ID/attempts"
expect_status 200
jq -e 'length == 3 and (map(.outcome) == ["FAILED","FAILED","SUCCEEDED"])' "$BODY_FILE" >/dev/null \
  || fail "expected 3 attempts with outcomes FAILED, FAILED, SUCCEEDED"

step "13. 410 endpoint: DEAD after 1 attempt (max 30s)"
request POST "$API/endpoints" -H 'Content-Type: application/json' \
  -d '{"url":"http://webhook-sink:8080/status/410","description":"smoke gone","eventTypes":["smoke.gone"]}'
expect_status 201
GONE_ENDPOINT_ID="$(jq -r '.id // empty' "$BODY_FILE")"
[ -n "$GONE_ENDPOINT_ID" ] || fail "410 endpoint response has no id"
EXTRA_ENDPOINT_IDS="$EXTRA_ENDPOINT_IDS $GONE_ENDPOINT_ID"
request POST "$API/events" -H 'Content-Type: application/json' \
  -d '{"eventType":"smoke.gone","payload":{"smoke":"gone"}}'
expect_status 202
GONE_DELIVERY_ID="$(jq -r --arg ep "$GONE_ENDPOINT_ID" '[.deliveries[] | select(.endpointId == $ep)][0].id // empty' "$BODY_FILE")"
[ -n "$GONE_DELIVERY_ID" ] || fail "no delivery for the 410 endpoint"
deadline=$((SECONDS + 30))
while :; do
  request GET "$API/deliveries/$GONE_DELIVERY_ID"
  expect_status 200
  DSTATUS="$(jq -r '.status // empty' "$BODY_FILE")"
  [ "$DSTATUS" != "DEAD" ] || break
  [ "$SECONDS" -lt "$deadline" ] || fail "410 delivery still '$DSTATUS' after 30s"
  sleep 1
done
jq -e '.attemptCount == 1 and ((.lastError // "") | contains("410"))' "$BODY_FILE" >/dev/null \
  || fail "expected DEAD with attemptCount 1 and lastError containing 410"

step "14. SSRF: endpoint targeting the metadata address is rejected (400)"
request POST "$API/endpoints" -H 'Content-Type: application/json' \
  -d '{"url":"http://169.254.169.254/latest","description":"smoke ssrf","eventTypes":["smoke.ssrf"]}'
if [ "$HTTP_STATUS" = "201" ]; then
  SSRF_ID="$(jq -r '.id // empty' "$BODY_FILE")"
  [ -z "$SSRF_ID" ] || EXTRA_ENDPOINT_IDS="$EXTRA_ENDPOINT_IDS $SSRF_ID"
fi
expect_status 400

step "15. delete all endpoints, then GET must be 404"
for id in $FLAKY_ENDPOINT_ID $GONE_ENDPOINT_ID; do
  request DELETE "$API/endpoints/$id"
  expect_status 204
done
EXTRA_ENDPOINT_IDS=""
request DELETE "$API/endpoints/$ENDPOINT_ID"
expect_status 204
DELETED_ID="$ENDPOINT_ID"
ENDPOINT_ID=""
request GET "$API/endpoints/$DELETED_ID"
expect_status 404

echo "SMOKE TEST PASSED"
