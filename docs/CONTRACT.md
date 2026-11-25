# hookrelay — Contract

This document is the base agreement every contributor (human or agent) follows.
Each deliverable adds its own contract in `docs/contracts/E<N>.md` (scope, API,
schema, file ownership); read both. Where they differ, the deliverable contract
wins. If something is not covered, choose the simplest option and record the
decision in your `docs/progress/<agent-name>.md`.

| Deliverable | Contract | Status |
|-------------|----------|--------|
| E0 — Foundations | this file (§1–§9) | merged |
| E1 — Endpoints & data model | [`docs/contracts/E1.md`](contracts/E1.md) | merged |
| E2 — Delivery pipeline | [`docs/contracts/E2.md`](contracts/E2.md) | merged |
| E3 — Retries, backoff & DLQ | [`docs/contracts/E3.md`](contracts/E3.md) | merged |
| E4 — Signing & security | [`docs/contracts/E4.md`](contracts/E4.md) | in progress |

E0 scope note: no business logic, REST endpoints (other than Actuator) or data
schema. That is E1.

## 1. Versions

| Item | Version | Notes |
|------|---------|-------|
| Java | **21** (Temurin) | Gradle toolchain `JavaLanguageVersion.of(21)` |
| Spring Boot | **3.5.16** | Latest stable 3.x at time of writing |
| Spring dependency management plugin | **1.1.7** | `io.spring.dependency-management` |
| Gradle | **8.14.7** via wrapper | Kotlin DSL (`*.gradle.kts`). Always use `./gradlew`, never a system Gradle |
| Spotless Gradle plugin | **8.11.0** | `com.diffplug.spotless` |
| google-java-format | latest that works with Spotless 8.11.0 on JDK 21 | Pin the exact version in `build.gradle.kts` |
| Testcontainers | managed by the Spring Boot BOM | Do not override unless it fails; if so, record why |
| PostgreSQL image | `postgres:16` | Same tag in compose and tests |
| RabbitMQ image | `rabbitmq:3-management` | Same tag in compose and tests |
| App runtime base image | `eclipse-temurin:21-jre-alpine` | Build stage: `eclipse-temurin:21-jdk` |

## 2. Identity and layout

- Gradle root project name: `hookrelay` (single module for now; future modules go
  under the root and are added to `settings.gradle.kts`).
- Group: `io.github.joseferrao20`; version: `0.0.1-SNAPSHOT`.
- Base package: `io.github.joseferrao20.hookrelay`.
- Main class: `io.github.joseferrao20.hookrelay.HookrelayApplication`.
- Boot jar file name: `build/libs/hookrelay.jar` (set `archiveFileName` on
  `bootJar`; disable the plain `jar` task).

```
.
├── .claude/agents/            # sub-agent definitions (coordinator)
├── .github/
│   ├── workflows/ci.yml       # ci-docs
│   ├── dependabot.yml         # ci-docs
│   └── pull_request_template.md  # ci-docs
├── docs/
│   ├── CONTRACT.md            # coordinator
│   ├── adr/                   # ci-docs: 0000-template.md, 0001-stack.md
│   └── progress/<agent>.md    # one per agent
├── gradle/wrapper/            # app-skeleton
├── src/main/java/io/github/joseferrao20/hookrelay/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/.gitkeep  # empty in E0
├── src/test/java/io/github/joseferrao20/hookrelay/
├── build.gradle.kts, settings.gradle.kts, gradlew, gradlew.bat, gradle.properties
├── docker-compose.yml, Dockerfile, .dockerignore, .env.example, Makefile, scripts/
├── README.md                  # ci-docs
└── PROGRESS.md                # coordinator only
```

### File ownership (prevents merge conflicts)

| Owner | Files |
|-------|-------|
| coordinator | `PROGRESS.md`, `docs/CONTRACT.md`, `.claude/**` |
| app-skeleton | `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew*`, `gradle/**`, `src/**`, `.gitignore`, `.gitattributes`, `.editorconfig` |
| local-infra | `docker-compose.yml`, `Dockerfile`, `.dockerignore`, `.env.example`, `Makefile`, `scripts/**` |
| ci-docs | `.github/**`, `README.md`, `docs/adr/**` |
| each agent | `docs/progress/<agent-name>.md` |

Do not create or edit files owned by another agent. If you need something from
another owner, assume it exists exactly as this contract describes it.

## 3. Ports

| Service | Port |
|---------|------|
| App (HTTP + Actuator) | 8080 |
| PostgreSQL | 5432 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ management UI | 15672 |

## 4. Database and environment variables

Database name: `hookrelay`. The app reads only the variables below; every one has
the example value as its default in `application.yml`, so the app starts against
the local compose stack with no env vars set.

| Variable | Example / default | Used by |
|----------|-------------------|---------|
| `SERVER_PORT` | `8080` | app |
| `DB_HOST` | `localhost` | app |
| `DB_PORT` | `5432` | app, compose (host port) |
| `DB_NAME` | `hookrelay` | app, compose (`POSTGRES_DB`) |
| `DB_USER` | `hookrelay` | app, compose (`POSTGRES_USER`) |
| `DB_PASSWORD` | `hookrelay` | app, compose (`POSTGRES_PASSWORD`) |
| `RABBITMQ_HOST` | `localhost` | app |
| `RABBITMQ_PORT` | `5672` | app, compose (host port) |
| `RABBITMQ_MANAGEMENT_PORT` | `15672` | compose (host port) |
| `RABBITMQ_USER` | `hookrelay` | app, compose (`RABBITMQ_DEFAULT_USER`) |
| `RABBITMQ_PASSWORD` | `hookrelay` | app, compose (`RABBITMQ_DEFAULT_PASS`) |
| `RABBITMQ_VHOST` | `/` | app, compose (`RABBITMQ_DEFAULT_VHOST`) |

Required `application.yml` mapping (app-skeleton):

```yaml
server:
  port: ${SERVER_PORT:8080}
spring:
  application:
    name: hookrelay
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:hookrelay}
    username: ${DB_USER:hookrelay}
    password: ${DB_PASSWORD:hookrelay}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
    locations: classpath:db/migration
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER:hookrelay}
    password: ${RABBITMQ_PASSWORD:hookrelay}
    virtual-host: ${RABBITMQ_VHOST:/}
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
```

Health endpoint: `GET http://localhost:8080/actuator/health` → `{"status":"UP",...}`
with `db` and `rabbit` components UP when the compose stack is running.

Inside the compose network the app container uses `DB_HOST=postgres` and
`RABBITMQ_HOST=rabbitmq` (service names below).

## 5. Docker Compose

- Service names: `postgres`, `rabbitmq`, `app`.
- `postgres` and `rabbitmq` start by default with `docker compose up -d`, both with
  healthchecks (`pg_isready` and `rabbitmq-diagnostics -q ping`) and named volumes
  (`postgres-data`, `rabbitmq-data`).
- `app` is behind the compose profile `app` (`docker compose --profile app up -d`),
  builds from the root `Dockerfile`, `depends_on` both services with
  `condition: service_healthy`, and has a healthcheck on `/actuator/health`.
- Compose reads `.env` if present; `.env.example` holds the example values above.

## 6. Standard commands

| Purpose | Command |
|---------|---------|
| Build + tests + lint | `./gradlew build` |
| Lint only | `./gradlew spotlessCheck` |
| Auto-format | `./gradlew spotlessApply` |
| Start infra | `docker compose up -d` (or `make up`) |
| Stop infra | `docker compose down` (or `make down`) |
| Infra logs | `docker compose logs -f` (or `make logs`) |
| Run app locally | `./gradlew bootRun` |
| Build app image | `docker build -t hookrelay:local .` |

`./gradlew build` must run `spotlessCheck` (Spotless wires it into `check` by
default — keep it that way). Tests use Testcontainers and need a running Docker
daemon.

## 7. Git conventions

- Default branch: `main`. Never push to `main` directly except the coordinator in
  Step 1.
- Agent branches: `e<N>/<topic>` (E0: `e0/app-skeleton`, `e0/local-infra`,
  `e0/ci-docs`). Integration branch: `feature/e<N>`.
- Commits: [Conventional Commits](https://www.conventionalcommits.org/) —
  `type(scope): summary` in English, imperative mood, ≤72 chars.
  Types: `feat`, `fix`, `build`, `ci`, `docs`, `test`, `chore`, `refactor`.
  Scopes for E0: `app`, `infra`, `ci`, `docs`.
- Commit **and push** every logical unit of work (≤ ~15 minutes of work). Never
  leave work uncommitted while waiting on something.
- After each completed step, update and commit your `docs/progress/<agent>.md`.
- Commit message trailer (all commits): a `Co-Authored-By:` line naming the model
  that actually authored the commit (agents follow their session's attribution
  instructions), e.g.
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  ```
- All code, comments, commits and docs are in English.

## 8. Progress file format (`docs/progress/<agent>.md`)

```markdown
# <agent-name>

Branch: e0/<topic>

| Task | Status (todo/doing/done) | Last commit | Next action |
|------|--------------------------|-------------|-------------|

## Decisions
- <decision> — <why>

## Problems
- <problem> — <status>
```

## 9. Definition of done for E0

- `docker compose up -d` starts Postgres and RabbitMQ and both become `healthy`.
- `./gradlew build` compiles, runs tests (context loads with Testcontainers) and
  passes `spotlessCheck`.
- GitHub Actions CI is green on the PR.
- `README.md` and `docs/adr/0001-stack.md` exist.
