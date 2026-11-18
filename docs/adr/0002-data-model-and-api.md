# 0002. Data model and API

## Status

Accepted

## Date

2026-11-18

## Context

E1 introduces endpoints, events and deliveries and the REST API around them. We
need a model that stores events durably, is safe for clients to retry, and
prepares delivery (E2) without adding messaging yet.

## Decision

- **Package-by-feature**: `endpoint`, `event`, `delivery`, plus `common` and
  `config`. Controllers talk to services; services own transactions. DTOs are
  records; entities are never serialized.
- **UUIDs generated in the application** (in entity constructors), so ids are
  known before insert and need no database round trip.
- **Soft delete for endpoints** (`deleted_at`): deleted endpoints return 404 and
  get no new deliveries, while existing deliveries keep their foreign key.
- **Event types as a join table** (`endpoint_event_types`) with the `*` wildcard
  meaning all types; indexed by event type for fan-out lookups.
- **Transactional fan-out at ingestion**: creating an event inserts one `PENDING`
  delivery per matching enabled endpoint in the same transaction. Dispatch
  (publishing to RabbitMQ and sending HTTP) is deferred to E2, so E1 has no
  broker coupling and nothing can be lost between "event stored" and "delivery
  planned".
- **Idempotency**: an optional `Idempotency-Key` header backed by a unique
  constraint. Same key and equal content returns `200` with the original event
  and creates no deliveries; different content returns `409`. Concurrent
  requests are decided by the constraint; the loser re-reads.
- **Errors** use RFC 9457 Problem Details (`application/problem+json`) with an
  `errors` list for validation failures; 500s expose no internals.
- **Endpoint secret** is generated server-side (`whsec_` + 32 random bytes) and
  returned only on creation. It is stored in plain text until E4.
- **No authentication** until E4.

## Alternatives

- Layer-based packages: simpler to start, but scatters one feature across the tree.
- Database-generated ids or sequences: extra round trip, ids unknown before flush.
- Hard delete of endpoints: breaks the history held by deliveries.
- Event types as a JSON or array column: harder to index and query for fan-out.
- Publishing to RabbitMQ at ingestion: needs an outbox or dual write; postponed to E2.
- Idempotency via application-level lookup only: racy without the constraint.

## Consequences

- Fan-out is atomic and easy to test; ingestion latency grows with the number of
  matching endpoints.
- E2 must pick up `PENDING` deliveries (for example with an outbox-style poller).
- Risk: plain-text secrets and an open API. Follow-up in E4: protect secrets,
  support rotation, add authentication.
- Soft-deleted endpoints accumulate rows; a purge job may be needed later.
