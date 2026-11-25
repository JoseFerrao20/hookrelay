# local-infra

Branch: e0/local-infra

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| compose + .env.example | done | see git log | - |
| Dockerfile + .dockerignore | done (not built, see Problems) | see git log | coordinator builds at integration |
| Makefile | done | see git log | - |
| verification | done | see git log | - |

## Decisions
- Compose project `name: hookrelay` set at top level so default project name is stable.
- `make up` uses `docker compose up -d --wait` so it returns once services are healthy.
- Dockerfile uses Spring Boot layered extraction (`-Djarmode=tools extract --layers`); runs `java -jar hookrelay.jar` from the extracted dir. Tests are skipped in the image build (`-x test`) since CI runs them.
- Dockerfile dependency-cache layer runs `./gradlew dependencies` (failure tolerated) before copying `src`.
- Container runtime user UID/GID 10001; JVM flags via `JAVA_TOOL_OPTIONS` (MaxRAMPercentage=75, ExitOnOutOfMemoryError).
- App healthcheck uses `wget` (busybox, present in alpine) against `/actuator/health` on `$SERVER_PORT`.
- Inside compose, app gets `DB_HOST=postgres`, `RABBITMQ_HOST=rabbitmq` and the in-network ports 5432/5672.

## Problems
- Docker Hub returned 429 on first pulls; resolved by retrying later.
- Dockerfile not built: app source and Gradle wrapper are not on this branch and no system Gradle is available to make a throwaway wrapper project; coordinator verifies at integration.
- `docker compose config` valid; `up -d --wait` brought postgres and rabbitmq to healthy (tested with project `hookrelay-infra-test`, removed with `down -v`).

## E1

Branch: e1/local-infra

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| scripts/smoke-test.sh | done (validated vs mock server) | see git log | coordinator runs it against the real app at integration |
| Makefile run/smoke/build/format | done | see git log | - |

### Decisions
- Delivery checks filter by the created endpoint's id, so other endpoints subscribed to `smoke.test` do not break the "exactly one delivery" assertion.
- Missing curl/jq exits 2; any failed step exits 1 and prints "SMOKE TEST FAILED at step: <n>". The script best-effort deletes its endpoint on failure.
- Compose/Dockerfile unchanged (contract does not require changes).

### Problems
- shellcheck not available; checked with `bash -n` and a throwaway Python mock server (pass, replay mismatch, bad delete status) kept outside the repo.

## E2

Branch: e2/local-infra

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| scripts/webhook-sink.py | done | see git log | - |
| compose `webhook-sink` (profile `sink`), SINK_PORT | done | see git log | - |
| smoke-test.sh new steps 6-8 | done (validated vs mock API) | see git log | coordinator runs against real app at integration |
| Makefile up-all/down-all | done | see git log | - |

### Decisions
- Sink records header names as received; the smoke test matches `Hookrelay-Delivery-Id` case-insensitively and compares the body as parsed JSON.
- `make clean` now also includes the `sink` profile.
- Sink healthcheck uses busybox `wget` on `/received`.

### Problems
- Real dispatch not on this branch; smoke steps validated against a throwaway mock API (outside the repo). Sink verified alone and as a compose service (project `hookrelay-e2-infra`, removed with `down -v`).

## E3

Branch: e3/local-infra

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| sink `/status/<code>` and `/flaky/<key>?fail=<n>` | done (curl-verified) | see git log | - |
| compose retry env vars + `.env.example` | done (`docker compose config` ok) | see git log | - |
| Makefile `up-all` fast retries | done | see git log | - |
| smoke-test.sh steps 9-11 (flaky, 410, cleanup + hint) | done (validated vs mock API) | see git log | coordinator runs against real app at integration |

### Decisions
- Original delete step is now step 11 and also deletes the flaky and 410 endpoints; failure cleanup removes them too.
- Sink: unknown/invalid `/status/<x>` or non-numeric `fail` falls back to 200 / fail=0.

### Problems
- Retry code is not on this branch; smoke steps 9-10 validated against a throwaway mock API (outside the repo). Sink verified as a compose service (project `hookrelay-e3-infra`, removed with `down -v`).

## E4

Branch: e4/local-infra

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| compose `app` security env (dev-only defaults) + `.env.example` | done (`docker compose config` ok) | see git log | - |
| sink records `body_b64` (exact raw bytes) | done | see git log | - |
| smoke-test.sh: Bearer key, 401, signature, rotation, SSRF, openssl required | done (validated vs mock API) | see git log | coordinator runs against real app at integration |

### Decisions
- Dev encryption key generated once with `openssl rand -base64 32` and hardcoded as the compose default and in `.env.example`.
- Sink keeps `body` (lossy UTF-8) and adds `body_b64`; the smoke test signs over the decoded `body_b64` bytes.
- Smoke steps renumbered 1-15 (401 = 2, signature = 10, rotation = 11, SSRF = 14).
- The rotation step posts a second `smoke.test` event, so it uses the main endpoint with default grace.

### Problems
- App E4 code not on this branch; the full flow passed against a throwaway mock API (outside the repo) that signs in Python, so openssl was checked against an independent HMAC implementation.
