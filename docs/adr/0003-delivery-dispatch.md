# 0003. Delivery dispatch

## Status

Accepted

## Date

2026-11-18

## Context

E1 creates `PENDING` deliveries in PostgreSQL. E2 must actually send them over
HTTP and record each outcome. We need a design that survives crashes, runs on
several app instances and never loses a delivery. Retries (E3), signing and SSRF
protection (E4) come later.

## Decision

- **PostgreSQL is the source of truth; RabbitMQ distributes work.** A delivery row
  is the authoritative state. Queue messages are only hints to do work.
- **Poller with `FOR UPDATE SKIP LOCKED` and leases.** `DeliveryPoller` selects due
  `PENDING` rows, sets `locked_until = now + lease` and bumps `version` in one
  short transaction. **After that transaction commits**, it publishes
  `{deliveryId, version}` to RabbitMQ and waits for publisher confirms.
  Publishing inside the transaction lost work. A fast worker could receive the
  message before the commit, see the old version and drop it, and the row stayed
  locked until its lease expired. If a publish fails, the leases are released
  (best effort) or simply expire. `SKIP LOCKED` makes several instances safe.
- **Version-based claims.** `DeliveryWorker` claims with
  `UPDATE ... WHERE id = :id AND version = :version AND status = 'PENDING'`. Only one
  claim succeeds per published version, so stale or duplicate messages are acked
  and ignored. The result is recorded with a second version-checked update, and an
  attempt row is always kept.
- **At-least-once delivery.** A second send is only possible when an attempt
  outlives its lease: the poller then republishes the row. Receivers dedupe on
  `Hookrelay-Delivery-Id`. The HTTP call runs outside any transaction.
- **No requeue on errors.** Worker exceptions are not requeued
  (`default-requeue-rejected: false`). The row stays `PENDING`, and the poller
  republishes it after the lease expires, so recovery has a single path.

## Alternatives

- **Transactional outbox library** (for example Debezium or a framework outbox):
  more moving parts for a single table that already acts as the outbox. The poller
  gives the same guarantee with plain SQL.
- **Pure polling workers (no broker):** simplest, but workers would poll the DB for
  both claiming and fan-out, with no push-based distribution, no natural
  prefetch/concurrency control and a harder path to delay queues for E3 backoff.
- **Publish to RabbitMQ directly in the request transaction:** a crash between
  commit and publish loses work (dual write).

## Consequences

- Positive: no lost deliveries after crashes, horizontal scaling, simple recovery,
  a clean base for E3 retries (`next_attempt_at`) and delay queues.
- Negative: up to `poll-interval` latency before the first send; the lease must
  exceed connect plus request timeout; duplicates are possible, so receivers must
  be idempotent.
- Known risks: endpoint URLs are not validated until E4, so a user can make the
  service call internal addresses (SSRF). Failures are final (`FAILED`) until E3
  adds retries and a DLQ.
