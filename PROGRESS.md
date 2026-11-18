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

## E1 — Endpoints & data model

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. Merge E0 (PR #1) | coordinator | main | done | 80711e2 | - |
| 1. E1 contract (docs/contracts/E1.md) | coordinator | main | done | b7f0702 | - |
| 2a. Domain, persistence and REST API | app-skeleton | e1/app-skeleton | doing | - | Running (see docs/progress/app-skeleton.md) |
| 2b. Smoke test script + Makefile targets | local-infra | e1/local-infra | doing | - | Running (see docs/progress/local-infra.md) |
| 2c. ADR-002, README API docs, CI smoke job | ci-docs | e1/ci-docs | doing | - | Running (see docs/progress/ci-docs.md) |
| 3. Integration (feature/e1) | coordinator | feature/e1 | todo | - | Merge 2a-2c, verify build, smoke test, CI |
| 4. PR "E1 — Endpoints & data model" | coordinator | feature/e1 | todo | - | Open PR to main |

## Resume protocol

1. Read this file and `docs/progress/*.md`.
2. Run `git status` and `git log --all --oneline -20`.
3. Continue from the "Next action" column. Never redo tasks marked `done`.
