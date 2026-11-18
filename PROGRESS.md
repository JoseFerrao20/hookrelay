# E0 — Foundations: progress

Single source of truth for the coordinator. Sub-agents do **not** edit this file;
they write their status to `docs/progress/<agent-name>.md`.

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. PROGRESS.md | coordinator | main | done | - | - |
| 1. Contract (docs/CONTRACT.md) | coordinator | main | done | 2ddecfc | - |
| 1b. Sub-agent definitions (.claude/agents/) | coordinator | main | done | f35a92a | - |
| 2a. Spring Boot skeleton | app-skeleton | e0/app-skeleton | done | 09754eb | Merge in integration |
| 2b. Local infra (compose, Dockerfile) | local-infra | e0/local-infra | done | 2abe5df | Build Dockerfile in integration |
| 2c. CI + docs (workflow, README, ADR-001) | ci-docs | e0/ci-docs | done | 992584b | Merge in integration (actionlint not run) |
| 3. Integration (feature/e0) | coordinator | feature/e0 | doing | e953b59 | Merged 3 branches, no conflicts; build, compose health and app health UP verified. Next: verify docker build |
| 4. PR "E0 — Foundations" | coordinator | feature/e0 | todo | - | Open PR to main (no merge) |

## Resume protocol

1. Read this file and `docs/progress/*.md`.
2. Run `git status` and `git log --all --oneline -20`.
3. Continue from the "Next action" column. Never redo tasks marked `done`.
