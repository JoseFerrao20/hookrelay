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
4. Failed deliveries are retried with backoff via delay queues, then dead-lettered.
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

# 3. List the resulting deliveries (PENDING, then SUCCEEDED or FAILED once dispatched)
curl -s 'http://localhost:8080/api/v1/deliveries?eventId=<EVENT_ID>'
```

With the app and infrastructure running, `make smoke` runs the same flow end to
end (`scripts/smoke-test.sh`, needs `curl` and `jq`). CI runs it on every PR.

Full stack with a local webhook sink (app container plus a receiver on port 8090):

```bash
make up-all     # docker compose --profile app --profile sink up -d --build --wait
make smoke      # creates an endpoint, sends an event, checks the sink received it
make down-all   # stops everything and removes volumes
```

## How delivery works

PostgreSQL is the source of truth and RabbitMQ distributes the work. Retries,
backoff and the DLQ arrive in E3: in E2 a failed attempt is final (`FAILED`).
Design notes: [ADR 0003](docs/adr/0003-delivery-dispatch.md).

```
POST /events ──tx──▶ deliveries (PENDING)
                          │  every poll-interval
                          ▼
                  DeliveryPoller ── claims due rows (FOR UPDATE SKIP LOCKED),
                          │         sets locked_until, bumps version
                          ▼
            RabbitMQ  exchange hookrelay.deliveries ─▶ queue hookrelay.deliveries.dispatch
                          │   message {deliveryId, version}
                          ▼
                  DeliveryWorker ── claim(id, version) ─▶ HTTP POST ─▶ record result + attempt
```

Delivery is at-least-once. Any 2xx response is success. Any other status
(redirects are not followed), an I/O error or a timeout is a failure. Every
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
| E2 (current) | Delivery pipeline |
| E3 | Retries, backoff and DLQ |
| E4 | Signing and security |
| E5 | Observability |
| E6 | Admin and replay |
| E7 | Hardening and release |
