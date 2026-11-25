# hookrelay

hookrelay is a reliable webhook delivery service. Producers submit events, and
hookrelay delivers them to subscriber endpoints with retries, exponential backoff,
signing and a dead-letter queue, so receivers never silently miss an event.

Stack: Java 21, Spring Boot 3, PostgreSQL, RabbitMQ. See
[ADR 0001](docs/adr/0001-stack.md).

## Planned architecture

1. A REST API accepts events and manages endpoints and subscriptions.
2. Events and delivery attempts are stored durably in PostgreSQL.
3. Each delivery is published to RabbitMQ and consumed by delivery workers.
4. Failed deliveries are retried with exponential backoff scheduled in PostgreSQL, then marked `DEAD`; unprocessable messages go to a RabbitMQ dead-letter queue.
5. Outgoing requests are HMAC-signed; metrics and health come from Actuator.

## Run locally

Prerequisites: JDK 21, Docker.

```bash
cp .env.example .env          # optional; defaults match the example values
docker compose up -d          # PostgreSQL (5432) and RabbitMQ (5672, UI on 15672)
./gradlew bootRun             # app on http://localhost:8080
curl http://localhost:8080/actuator/health
```

To run the app in a container as well:

```bash
docker build -t hookrelay:local .
docker compose --profile app up -d
```

Stop the infrastructure with `docker compose down`, and follow logs with
`docker compose logs -f`. `make up`, `make down` and `make logs` are shortcuts.

### Ports

| Service | Port |
|---------|------|
| App (HTTP + Actuator) | 8080 |
| PostgreSQL | 5432 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ management UI | 15672 |

### Configuration

All variables are optional; the defaults work with the local compose stack.

| Variable | Default |
|----------|---------|
| `SERVER_PORT` | `8080` |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `hookrelay` / `hookrelay` / `hookrelay` |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` |
| `RABBITMQ_MANAGEMENT_PORT` | `15672` (compose host port) |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `hookrelay` / `hookrelay` |
| `RABBITMQ_VHOST` | `/` |

## API

Base path `/api/v1`, JSON, no authentication yet (E4). Interactive docs: Swagger
UI at <http://localhost:8080/swagger-ui.html> (spec at `/v3/api-docs`). Design
notes: [ADR 0002](docs/adr/0002-data-model-and-api.md).

| Method and path | Purpose |
|-----------------|---------|
| `POST /api/v1/endpoints` | Create an endpoint (secret returned once) |
| `GET /api/v1/endpoints` | List endpoints (`page`, `size`) |
| `GET /api/v1/endpoints/{id}` | Get an endpoint |
| `PATCH /api/v1/endpoints/{id}` | Update an endpoint |
| `DELETE /api/v1/endpoints/{id}` | Soft-delete an endpoint |
| `POST /api/v1/events` | Submit an event (optional `Idempotency-Key` header) |
| `GET /api/v1/events` | List events (`eventType`, `page`, `size`) |
| `GET /api/v1/events/{id}` | Get an event with its deliveries |
| `GET /api/v1/deliveries` | List deliveries (`eventId`, `endpointId`, `status`) |
| `GET /api/v1/deliveries/{id}` | Get a delivery |
| `GET /api/v1/deliveries/{id}/attempts` | List the delivery's attempts |

Happy path:

```bash
# 1. Register an endpoint subscribed to order.created
curl -s -X POST http://localhost:8080/api/v1/endpoints \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/webhooks","eventTypes":["order.created"]}'

# 2. Submit an event (202 on first call, 200 on an idempotent replay)
curl -s -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-42' \
  -d '{"eventType":"order.created","payload":{"orderId":42}}'

# 3. List the resulting deliveries (PENDING, then SUCCEEDED, FAILED while a retry is scheduled, or DEAD)
curl -s 'http://localhost:8080/api/v1/deliveries?eventId=<EVENT_ID>'
```

With the app and infrastructure running, `make smoke` runs the same flow end to
end, including a flaky endpoint that succeeds on attempt 3 and a `410` endpoint that
ends `DEAD` (`scripts/smoke-test.sh`, needs `curl` and `jq` and a stack running with
fast retries). CI runs it on every PR.

Full stack with a local webhook sink (app container plus a receiver on port 8090):

```bash
make up-all     # app + sink, with fast retries (1s initial delay, 5s max, no jitter)
make smoke      # creates an endpoint, sends an event, checks the sink received it
make down-all   # stops everything and removes volumes
```

## How delivery works

PostgreSQL is the source of truth and RabbitMQ distributes the work. Retries
are scheduled in PostgreSQL (`next_attempt_at`) and picked up by the same poller.
Design notes: [ADR 0003](docs/adr/0003-delivery-dispatch.md) and
[ADR 0004](docs/adr/0004-retries-and-dead-letters.md).

```
POST /events ──tx──▶ deliveries (PENDING)
                          │  every poll-interval (PENDING or FAILED, due)
                          ▼
                  DeliveryPoller ── claims due rows (FOR UPDATE SKIP LOCKED),
                          │         sets locked_until, bumps version
                          ▼
            RabbitMQ  exchange hookrelay.deliveries ─▶ queue hookrelay.deliveries.work
                          │   message {deliveryId, version}
                          ▼
                  DeliveryWorker ── claim(id, version) ─▶ HTTP POST ─▶ record result + attempt
```

### Delivery states

```
            ┌──────── retryable failure, attempts left ────────┐
            ▼                                                   │
PENDING ──attempt──▶ SUCCEEDED (terminal)                      │
   │                                                            │
   └──attempt fails──▶ FAILED (next_attempt_at = now + backoff) ┘
                         │
                         └── no attempts left / non-retryable ──▶ DEAD (terminal)
```

`FAILED` means the last attempt failed and a retry is scheduled. `SUCCEEDED` and
`DEAD` are terminal. `DEAD` also covers `410 Gone` (never retried) and
deliveries whose endpoint was deleted or disabled. `lastError` says why, for
example `HTTP 500 (gave up after 10 attempts)`.

### Retry policy

Delay after failed attempt N: `min(max-delay, initial-delay x multiplier^(N-1))`,
randomised by +/- `jitter`. For `429` and `503`, a valid `Retry-After` header
raises the wait (capped at `max-delay`). Properties live under `hookrelay.retry`.

| Property | Env var | Default |
|----------|---------|---------|
| `max-attempts` | `HOOKRELAY_RETRY_MAX_ATTEMPTS` | `10` |
| `initial-delay` | `HOOKRELAY_RETRY_INITIAL_DELAY` | `30s` |
| `multiplier` | `HOOKRELAY_RETRY_MULTIPLIER` | `2.0` |
| `max-delay` | `HOOKRELAY_RETRY_MAX_DELAY` | `6h` |
| `jitter` | `HOOKRELAY_RETRY_JITTER` | `0.2` |

The default schedule is 30s, 1m, 2m, 4m, 8m, 16m, 32m, 64m, 128m (about 4.3 h
in total). `make up-all` and the CI smoke job use fast retries
(`HOOKRELAY_RETRY_INITIAL_DELAY=1s`, `HOOKRELAY_RETRY_MAX_DELAY=5s`,
`HOOKRELAY_RETRY_JITTER=0`) so the smoke test finishes quickly.

### Dead-letter queue

`DEAD` deliveries live only in PostgreSQL. The RabbitMQ DLQ
`hookrelay.deliveries.dlq` (via exchange `hookrelay.deliveries.dlx`) receives
messages the worker could not process: an exception while handling, or a message
that cannot be converted. Nothing consumes it yet (redrive comes in E6), and the
poller still recovers the delivery row after its lease expires. On startup the
app deletes the old E2 queue `hookrelay.deliveries.dispatch`.

Delivery is at-least-once. Any 2xx response is success. Any other status
(redirects are not followed), an I/O error or a timeout is a failure and is
retried, except `410 Gone`, which is terminal. Every
attempt is recorded and visible at `GET /api/v1/deliveries/{id}/attempts`.

### What receivers get

Each delivery is an HTTP `POST` with the event payload as the JSON body and these
headers:

| Header | Value |
|--------|-------|
| `Content-Type` | `application/json` |
| `User-Agent` | `hookrelay/<app version>` |
| `Hookrelay-Delivery-Id` | Delivery UUID |
| `Hookrelay-Event-Id` | Event UUID |
| `Hookrelay-Event-Type` | Event type |
| `Hookrelay-Attempt` | Attempt number, starting at 1 |
| `Hookrelay-Timestamp` | Send time, Unix epoch seconds |

Dedupe advice: a delivery can occasionally be sent more than once (for example,
when an attempt outlives its lease). Store `Hookrelay-Delivery-Id` and ignore
repeats, and make handlers idempotent. Respond with a 2xx quickly, and do the
heavy work asynchronously.

### Dispatch configuration

Properties live under `hookrelay.dispatch`; each can be set by environment variable.

| Property | Env var | Default |
|----------|---------|---------|
| `enabled` | `HOOKRELAY_DISPATCH_ENABLED` | `true` |
| `poll-interval` | `HOOKRELAY_DISPATCH_POLL_INTERVAL` | `1s` |
| `batch-size` | `HOOKRELAY_DISPATCH_BATCH_SIZE` | `100` |
| `lease` | `HOOKRELAY_DISPATCH_LEASE` | `60s` (must exceed connect + request timeout) |
| `connect-timeout` | `HOOKRELAY_DISPATCH_CONNECT_TIMEOUT` | `5s` |
| `request-timeout` | `HOOKRELAY_DISPATCH_REQUEST_TIMEOUT` | `10s` |
| `consumers` | `HOOKRELAY_DISPATCH_CONSUMERS` | `4` |

With `enabled=false` neither the poller nor the listener runs; the API works as before.
Until E4, endpoint URLs are not restricted, so do not expose a development instance
to untrusted users (SSRF risk).

## Development

| Purpose | Command |
|---------|---------|
| Build + tests + lint | `./gradlew build` |
| Lint only | `./gradlew spotlessCheck` |
| Auto-format | `./gradlew spotlessApply` |

Tests use Testcontainers and need a running Docker daemon. CI runs on every
pull request and push to `main`. Commits follow
[Conventional Commits](https://www.conventionalcommits.org/).

## Roadmap

| Milestone | Scope |
|-----------|-------|
| E0 | Foundations: project skeleton, local infrastructure, CI, docs |
| E1 | Endpoints and data model |
| E2 | Delivery pipeline |
| E3 (current) | Retries, backoff and DLQ |
| E4 | Signing and security |
| E5 | Observability |
| E6 | Admin and replay |
| E7 | Hardening and release |
