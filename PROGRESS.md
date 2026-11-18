# E0 — Foundations: progress

Single source of truth for the coordinator. Sub-agents do **not** edit this file;
they write their status to `docs/progress/<agent-name>.md`.

| Task | Owner | Branch | Status | Last commit | Next action |
|------|-------|--------|--------|-------------|-------------|
| 0. PROGRESS.md | coordinator | main | done | (this commit) | Write docs/CONTRACT.md |
| 1. Contract (docs/CONTRACT.md) | coordinator | main | todo | - | Write contract with versions, layout, ports, env vars, commands, conventions |
| 1b. Sub-agent definitions (.claude/agents/) | coordinator | main | todo | - | Create app-skeleton, local-infra, ci-docs |
| 2a. Spring Boot skeleton | app-skeleton | e0/app-skeleton | todo | - | Launch after contract is on main |
| 2b. Local infra (compose, Dockerfile) | local-infra | e0/local-infra | todo | - | Launch after contract is on main |
| 2c. CI + docs (workflow, README, ADR-001) | ci-docs | e0/ci-docs | todo | - | Launch after contract is on main |
| 3. Integration (feature/e0) | coordinator | feature/e0 | todo | - | Merge 2a-2c, verify build/compose/health |
| 4. PR "E0 — Foundations" | coordinator | feature/e0 | todo | - | Open PR to main (no merge) |

## Resume protocol

1. Read this file and `docs/progress/*.md`.
2. Run `git status` and `git log --all --oneline -20`.
3. Continue from the "Next action" column. Never redo tasks marked `done`.
