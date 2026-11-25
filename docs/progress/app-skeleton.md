# app-skeleton

Branch: e0/app-skeleton

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| Gradle wrapper + build files | done | see git log | - |
| Application, config, tests | done | see git log | - |
| Dotfiles | done | see git log | - |

## Decisions
- google-java-format pinned to 1.28.0 — works with Spotless 8.11.0 on JDK 21.
- Test uses `@Testcontainers` + `@ServiceConnection` static containers, RANDOM_PORT, asserts `/actuator/health` UP.
- `.gitignore` also ignores `.claude/worktrees/`.
- Commit trailer follows the session attribution (Claude Sonnet 5.5), which overrides the contract's Opus line.

## Problems
- None. `./gradlew build`, `spotlessCheck` and `bootJar` verified locally.

## E1

Branch: e1/app-skeleton

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| Migration + entities + repositories | done | see git log | - |
| Common errors, pagination, config | done | see git log | - |
| Endpoint feature | done | see git log | - |
| Event + delivery features | done | see git log | - |
| Tests (slices + integration) and cleanup | done | see git log | - |

### Decisions
- `EventTypes` (regex constants) lives in `common` — shared by endpoint and event DTOs.
- `EventService.create` uses a `TransactionTemplate` instead of `@Transactional`: on an idempotency-key race the loser's transaction is aborted by the unique constraint and must re-read the winner in a fresh transaction.
- Payload-is-object check is an `@AssertTrue` on `CreateEventRequest` (error field `payloadObject`).
- `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`; both `MethodArgumentNotValidException` and `HandlerMethodValidationException` (raised when a method has constrained params like the `Idempotency-Key` header) yield the `errors` array.
- Delivery filters use `JpaSpecificationExecutor` (avoids Postgres null-parameter typing issues).
- `PATCH` cannot clear `description` (null = unchanged, per contract).
- Idempotent replay compares payloads with Jackson `JsonNode.equals` (key order irrelevant, `1` vs `1.0` differ).

### Problems
- None. `./gradlew build` green (21 tests incl. Testcontainers, concurrency race test, Spotless).

## E2

Branch: e2/app-skeleton

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| Progress section | done | see git log | - |
| V2 migration + entities/repositories | done | see git log | - |
| Properties + topology + sender | done | see git log | - |
| Poller + worker + integration tests | done | see git log | - |
| Attempts endpoint + remaining tests | done | see git log | final ./gradlew build |

### Decisions
- Poller leases rows in its own short transaction and publishes (with publisher confirms) AFTER it ends, instead of inside it as contract 2.1 says: a worker can get the message before the poller transaction ends, miss the bumped version and drop it (observed in tests; the row then waits for lease expiry). On publish failure the leases are released best effort, otherwise they expire.
- Lease query is one `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id, version`, equivalent to select-then-update.
- Claim also loads event and endpoint in the same short transaction; the endpoint disabled/deleted check happens there.
- `CAST(:param AS ...)` on nullable params of the native record update (Postgres null typing).
- Message converter bean is a Jackson2JsonMessageConverter on the app ObjectMapper.

### Problems
- None open.

## E3

Branch: e3/app-skeleton

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| Progress section | done | see git log | - |
| RetryPolicy + unit tests | done | see git log | - |
| V3 migration + repository changes | done | see git log | - |
| Worker decision + Retry-After + integration tests | done | see git log | - |
| DLQ topology + legacy cleanup + tests | done | see git log | - |

### Decisions
- `RetryPolicy` takes an injectable `DoubleSupplier` random; `RetryPolicy.dead(reason)` is a static helper for blocked (deleted/disabled) endpoints, which skip the HTTP attempt.
- Retry-After parsing lives in `WebhookSender.parseRetryAfter` (package-private, unit-tested in `RetryPolicyTest`); HTTP-date in the past gives zero.
- Legacy queue cleanup is a `SmartInitializingSingleton` bean in `DispatchConfig` (idempotent, logs INFO only when something was deleted).
- Legacy cleanup is tested by declaring the old queue in the test and invoking the bean again, not by pre-declaring before context start.

### Problems
- None. `./gradlew build` green.

## E4

Branch: e4/app-skeleton

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| Progress section | done | see git log | - |
| API-key auth + shared test setup | doing | - | SecurityConfig, filter, test config |
| V4 + SecretCipher + backfill | todo | - | - |
| WebhookSignature + signing in sender | todo | - | - |
| Rotation endpoint | todo | - | - |
| SSRF classifier + save-time validation | todo | - | - |
| HttpClient 5 + send-time resolver + DEAD | todo | - | - |
| Remaining tests | todo | - | - |

### Decisions
- Shared test properties live in `src/test/resources/config/application.yml` (merged with, and overriding, the main `application.yml`), so every `@SpringBootTest` gets the API key, encryption key and `allow-private-networks=true`.

### Problems
- None yet.
