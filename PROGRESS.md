# hookrelay: progress

Single source of truth for the coordinator. Sub-agents do **not** edit this file;
they write their status to `docs/progress/<agent-name>.md`.

## E0 — Foundations (PR https://github.com/JoseFerrao20/hookrelay/pull/1, merged)

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. PROGRESS.md | coordinator | main | done | - | - |
| 1. Contract (docs/CONTRACT.md) | coordinator | main | done | 2ddecfc | - |
| 1b. Sub-agent definitions (.claude/agents/) | coordinator | main | done | f35a92a | - |
| 2a. Spring Boot skeleton | app-skeleton | e0/app-skeleton | done | 09754eb | - |
| 2b. Local infra (compose, Dockerfile) | local-infra | e0/local-infra | done | 2abe5df | - (Dockerfile verified in integration + CI) |
| 2c. CI + docs (workflow, README, ADR-001) | ci-docs | e0/ci-docs | done | 992584b | - |
| 3. Integration (feature/e0) | coordinator | feature/e0 | done | 5ab82dc | - |
| 4. PR "E0 — Foundations" | coordinator | feature/e0 | done | 198e62b | Owner review of https://github.com/JoseFerrao20/hookrelay/pull/1 (CI green) |

## E1 — Endpoints & data model (PR https://github.com/JoseFerrao20/hookrelay/pull/9, merged)

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. Merge E0 (PR #1) | coordinator | main | done | 80711e2 | - |
| 1. E1 contract (docs/contracts/E1.md) | coordinator | main | done | b7f0702 | - |
| 2a. Domain, persistence and REST API | app-skeleton | e1/app-skeleton | done | c1aa03d | - |
| 2b. Smoke test script + Makefile targets | local-infra | e1/local-infra | done | 5456d7d | Run against real API in integration (tested vs mock only) |
| 2c. ADR-002, README API docs, CI smoke job | ci-docs | e1/ci-docs | done | bfbef4e | Smoke job validated by CI on the PR |
| 3. Integration (feature/e1) | coordinator | feature/e1 | done | bd012e4 | - |
| 4. PR "E1 — Endpoints & data model" | coordinator | feature/e1 | done | c43994c | Owner review/merge of https://github.com/JoseFerrao20/hookrelay/pull/9 (CI green: build + smoke) |

## E2 — Delivery pipeline (PR https://github.com/JoseFerrao20/hookrelay/pull/10, merged)

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. Merge E1 (PR #9) | coordinator | main | done | 7472979 | - |
| 1. E2 contract (docs/contracts/E2.md) | coordinator | main | done | 30ed16c | - |
| 2a. Dispatcher, worker, HTTP sender, attempts API | app-skeleton | e2/app-skeleton | done | 7b378da | - (deviation: publish after lease commit) |
| 2b. Webhook sink + E2 smoke test | local-infra | e2/local-infra | done | 8d543db | Run smoke against real app in integration (mock only so far) |
| 2c. ADR-003, README, CI smoke with sink | ci-docs | e2/ci-docs | done | 0d30fde | Validated by CI on the PR |
| 3. Integration (feature/e2) | coordinator | feature/e2 | done | 03ccd5a | - |
| 4. PR "E2 — Delivery pipeline" | coordinator | feature/e2 | done | ddabf67 | Owner review/merge of https://github.com/JoseFerrao20/hookrelay/pull/10 (CI green: build + smoke) |

## E3 — Retries, backoff & DLQ (PR https://github.com/JoseFerrao20/hookrelay/pull/11, merged)

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. Merge E2 (PR #10) | coordinator | main | done | a89e6d9 | - |
| 1. E3 contract (docs/contracts/E3.md) | coordinator | main | done | cf8da25 | - |
| 2a. Retry policy, rescheduling, DEAD, DLQ topology | app-skeleton | e3/app-skeleton | done | 032df91 | - |
| 2b. Sink flaky/status routes, fast-retry env, E3 smoke | local-infra | e3/local-infra | done | ddae424 | Run smoke against real app in integration (mock only so far) |
| 2c. ADR-004, README, CI fast-retry env | ci-docs | e3/ci-docs | done | b29de43 | Validated by CI on the PR |
| 3. Integration (feature/e3) | coordinator | feature/e3 | done | 85c0113 | - |
| 4. PR "E3 — Retries, backoff & DLQ" | coordinator | feature/e3 | done | 15a4a29 | Owner review/merge of https://github.com/JoseFerrao20/hookrelay/pull/11 (CI green: build + smoke) |

## E4 — Signing & security

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. Merge E3 (PR #11) | coordinator | main | done | 12936fb | - |
| 1. E4 contract (docs/contracts/E4.md) | coordinator | main | done | 901366b | - |
| 2a. Signing, rotation, encryption, SSRF, API keys | app-skeleton | e4/app-skeleton | doing | - | Running (see docs/progress/app-skeleton.md) |
| 2b. Security env in compose, E4 smoke (auth, signature, rotation, SSRF) | local-infra | e4/local-infra | done | 0e8f414 | Run smoke against real app in integration (mock only so far) |
| 2c. ADR-005, README security + verification guide, Dependabot majors | ci-docs | e4/ci-docs | done | f6b6bfc | Verify README signature guide in integration |
| 3. Integration (feature/e4) | coordinator | feature/e4 | todo | - | Merge 2a-2c, verify build, smoke test |
| 4. PR "E4 — Signing & security" | coordinator | feature/e4 | todo | - | Open PR to main, watch CI |

Open Dependabot PRs (owner decision, not touched): #15 Spring Boot 4, #12 springdoc 3, #13 Gradle 9, #3 Temurin 25 conflict with the contract (Boot 3.x / Java 21); #14 spotless and #2/#4/#5/#7 GitHub Actions look safe.

## Resume protocol

1. Read this file and `docs/progress/*.md`.
2. Run `git status` and `git log --all --oneline -20`.
3. Continue from the "Next action" column. Never redo tasks marked `done`.
