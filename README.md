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

# 3. List the resulting deliveries (status PENDING until E2)
curl -s 'http://localhost:8080/api/v1/deliveries?eventId=<EVENT_ID>'
```

With the app and infrastructure running, `make smoke` runs the same flow end to
end (`scripts/smoke-test.sh`, needs `curl` and `jq`). CI runs it on every PR.

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
| E1 (current) | Endpoints and data model |
| E2 | Delivery pipeline |
| E3 | Retries, backoff and DLQ |
| E4 | Signing and security |
| E5 | Observability |
| E6 | Admin and replay |
| E7 | Hardening and release |
