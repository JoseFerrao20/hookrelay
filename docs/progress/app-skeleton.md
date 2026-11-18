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
