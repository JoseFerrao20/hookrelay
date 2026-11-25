# 0004. Retries and dead letters

## Status

Accepted. Supersedes the "retry queues" wording in [ADR 0001](0001-stack.md).

## Date

2026-11-18

## Context

E2 treats a failed attempt as final. E3 needs retries with exponential backoff,
a terminal state for deliveries that will never succeed, and a place for
messages the worker cannot process. ADR 0001 mentioned RabbitMQ "retry queues"
for this; we now have a poller that already finds due work in PostgreSQL
(ADR 0003), so we reconsidered.

## Decision

**Retries live in PostgreSQL.** A failed attempt sets the delivery to `FAILED`
with `next_attempt_at = now + delay(N)`. The existing poller picks up `PENDING`
and `FAILED` rows whose `next_attempt_at` is due and republishes them. No
RabbitMQ TTL or delay queues are used. This supersedes the "retry queues"
wording in ADR 0001.

**Backoff.** `delay(N)` is the wait after failed attempt `N` (1-based):

```
base(N)  = min(max-delay, initial-delay * multiplier^(N-1))
delay(N) = base(N) * (1 + U(-jitter, +jitter)), clamped to [0, max-delay]
```

Defaults: `max-attempts = 10`, `initial-delay = 30s`, `multiplier = 2.0`,
`max-delay = 6h`, `jitter = 0.2`. The nominal schedule is 30s, 1m, 2m, 4m, 8m,
16m, 32m, 64m, 128m, about 4.3 hours in total.

**What is retried.** Every failure except `410 Gone`: any non-2xx status
(3xx and 4xx included), I/O errors and timeouts. Receivers that are briefly
misconfigured, for example returning 401 during a key rotation, still recover.
`410` is the receiver saying "never send this again", so it ends the delivery
as `DEAD` at once. After `max-attempts` failures the delivery is also `DEAD`,
with `last_error` suffixed ` (gave up after N attempts)`. A deleted or disabled
endpoint makes its pending deliveries `DEAD` without sending.

**Retry-After.** For `429` and `503` with a valid `Retry-After` (delta-seconds
or HTTP-date) the wait is `max(delay(N), retryAfter)`, capped at `max-delay`.
A missing or invalid header falls back to `delay(N)`.

**`DEAD` versus the DLQ.** `DEAD` is a business outcome, recorded in
PostgreSQL only. The RabbitMQ dead-letter queue (`hookrelay.deliveries.dlq`) is
technical: it holds messages the listener rejected, such as a worker exception
or an unconvertible (poison) body. The delivery row is still recovered by the
poller after its lease expires, so the DLQ never holds the only copy of work.

**Queue rename and legacy cleanup.** A queue cannot gain
`x-dead-letter-exchange` in place (redeclaring fails with
`PRECONDITION_FAILED`). E3 therefore uses a new work queue
`hookrelay.deliveries.work` with a dead-letter exchange
`hookrelay.deliveries.dlx` and queue `hookrelay.deliveries.dlq`. On startup the
app deletes the E2 queue `hookrelay.deliveries.dispatch`, otherwise its binding
would keep collecting messages nobody consumes. Its in-flight messages are
safe to lose because the poller republishes after the lease expires.

## Alternatives

- **RabbitMQ TTL/delay queues per backoff step.** Fixed topology per delay,
  hard to apply jitter or `Retry-After`, and the retry state would be invisible
  in the database and the API. Rejected.
- **The delayed-message exchange plugin.** Adds a broker plugin dependency and
  still keeps the schedule outside PostgreSQL. Rejected.
- **Retry only 5xx and timeouts.** Fewer pointless attempts, but permanent
  misconfiguration that is fixed shortly after would lose events. Rejected.
- **Route `DEAD` deliveries to the DLQ.** Mixes business outcomes with
  technical failures and duplicates state. Rejected.

## Consequences

- Retry state is queryable (`status`, `nextAttemptAt`, `attemptCount`,
  `/attempts`) and survives broker loss.
- Backoff is tunable per deployment through `hookrelay.retry.*`.
- Retry latency is bounded below by the poll interval.
- Failing receivers that return 4xx still receive up to `max-attempts` requests.
- Follow-ups: E5 adds alerts on `DEAD` counts and DLQ depth; E6 adds manual
  replay of `DEAD` deliveries and DLQ redrive.
