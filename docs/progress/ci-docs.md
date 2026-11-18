# ci-docs

Branch: e0/ci-docs

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| CI workflow | done | see git log | - |
| Dependabot + PR template | done | see git log | - |
| ADRs | done | see git log | - |
| README | done | see git log | - |

## Decisions
- Commit trailer follows the session attribution reminder (Claude Sonnet 5.5) — it supersedes the contract's trailer.

## Problems
- none

## E1

Branch: e1/ci-docs

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| CI smoke job | done | see git log | - |
| ADR 0002 | done | see git log | - |
| README API section, roadmap | done | see git log | - |

### Decisions
- Smoke job uses `--profile app` for logs and teardown too, so the app container is included.

### Problems
- none

## E2

Branch: e2/ci-docs

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| CI smoke job (app + sink profiles) | done | see git log | - |
| ADR 0003 | done | see git log | - |
| README delivery docs, roadmap | done | see git log | - |

### Decisions
- Added the attempts endpoint to the README API table.

### Problems
- none

## E3

Branch: e3/ci-docs

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|
| CI smoke fast-retry env | done | see git log | - |
| ADR 0004 + ADR 0001 pointer | done | see git log | - |
| README updates | done | see git log | - |

### Decisions
- Wrote the retry env vars in the Start stack step only, as the contract says.

### Problems
- none
