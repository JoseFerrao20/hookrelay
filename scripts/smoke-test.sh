#!/usr/bin/env bash
# End-to-end smoke test against a running hookrelay stack (see docs/contracts/E1.md §6).
# Usage: scripts/smoke-test.sh [BASE_URL]   (default http://localhost:8080)
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
BASE_URL="${BASE_URL%/}"
API="$BASE_URL/api/v1"

for tool in curl jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required tool '$tool' is not installed or not on PATH" >&2
    exit 2
  fi
done

STEP="init"
ENDPOINT_ID=""
HTTP_STATUS=""
BODY_FILE="$(mktemp)"
HEADERS_FILE="$(mktemp)"

cleanup() {
  local rc=$?
  # Best-effort removal of the endpoint if we fail before the delete step.
  if [ "$rc" -ne 0 ] && [ -n "$ENDPOINT_ID" ]; then
    curl -s -o /dev/null -X DELETE "$API/endpoints/$ENDPOINT_ID" || true
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
    -X "$method" "$url" "$@")" || fail "request failed: $method $url"
}

expect_status() {
  [ "$HTTP_STATUS" = "$1" ] || fail "expected HTTP $1 but got $HTTP_STATUS"
}

step "1. wait for $BASE_URL/actuator/health to report UP (max 60s)"
deadline=$((SECONDS + 60))
until curl -s --max-time 5 "$BASE_URL/actuator/health" 2>/dev/null | jq -e '.status == "UP"' >/dev/null 2>&1; do
  [ "$SECONDS" -lt "$deadline" ] || fail "application did not become healthy within 60s"
  sleep 2
done

step "2. create endpoint"
request POST "$API/endpoints" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/smoke","description":"smoke test","eventTypes":["smoke.test"]}'
expect_status 201
ENDPOINT_ID="$(jq -r '.id // empty' "$BODY_FILE")"
[ -n "$ENDPOINT_ID" ] || fail "response has no id"
jq -e '.secret | type == "string" and length > 0' "$BODY_FILE" >/dev/null \
  || fail "response has no secret"

step "3. post event with Idempotency-Key"
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

step "4. replay same request, expect 200 and same event id"
request POST "$API/events" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d "$EVENT"
expect_status 200
REPLAY_ID="$(jq -r '.id // empty' "$BODY_FILE")"
[ "$REPLAY_ID" = "$EVENT_ID" ] || fail "replay returned event id '$REPLAY_ID', expected '$EVENT_ID'"

step "5. read delivery by eventId"
request GET "$API/deliveries?eventId=$EVENT_ID"
expect_status 200
jq -e --arg ep "$ENDPOINT_ID" \
  '[.content[] | select(.endpointId == $ep and .status == "PENDING")] | length == 1' \
  "$BODY_FILE" >/dev/null || fail "delivery for the endpoint not found with status PENDING"

step "6. delete endpoint, then GET must be 404"
request DELETE "$API/endpoints/$ENDPOINT_ID"
expect_status 204
DELETED_ID="$ENDPOINT_ID"
ENDPOINT_ID=""
request GET "$API/endpoints/$DELETED_ID"
expect_status 404

echo "SMOKE TEST PASSED"
