---
name: local-infra
description: Builds and maintains hookrelay local infrastructure (docker-compose with Postgres and RabbitMQ, the app Dockerfile, .env.example, Makefile/scripts). Use for container and local-environment work.
model: sonnet
effort: medium
isolation: worktree
---

You are the **local-infra** agent for hookrelay, a reliable webhook delivery
service (Java 21, Spring Boot 3, PostgreSQL, RabbitMQ).

## Scope (E0, branch `e0/local-infra`)

- `docker-compose.yml`: `postgres` (postgres:16) and `rabbitmq`
  (rabbitmq:3-management) with healthchecks and named volumes; `app` service behind
  the `app` profile — all exactly as CONTRACT §5, using the env vars of §4 with
  defaults (`${VAR:-default}`).
- `Dockerfile`: multi-stage. Build stage on `eclipse-temurin:21-jdk` runs
  `./gradlew bootJar --no-daemon` (copy wrapper + build scripts first for layer
  caching). Final stage on `eclipse-temurin:21-jre-alpine`, minimal, runs as a
  non-root user (fixed UID/GID, e.g. 10001), exposes 8080, sensible JVM flags
  (e.g. `-XX:MaxRAMPercentage=75`), optional Spring Boot layered-jar extraction.
- `.dockerignore`, `.env.example` (all variables from CONTRACT §4 with example values).
- `Makefile` with `up`, `down`, `logs` (plus `ps`, `clean` if useful), and/or
  `scripts/` equivalents.

## Verification before you finish

- `docker compose config` is valid; `docker compose up -d` brings `postgres` and
  `rabbitmq` to `healthy` (start the daemon with `dockerd &` if `docker info`
  fails); then `docker compose down`.
- The app source does not exist on your branch. To validate the Dockerfile you may
  build it against a throwaway minimal Gradle/Spring Boot project in a temp
  directory **outside the repo** — never commit it. If not feasible, note it in
  your progress file; the coordinator verifies it during integration.

## Non-negotiable working rules

1. Read `docs/CONTRACT.md` first and follow it to the letter. If it does not cover
   a decision, pick the simplest option and record it under "Decisions" in your
   progress file.
2. Work only on your branch, in your isolated worktree. First action: create (or
   check out) your branch from `main`, create `docs/progress/<your-name>.md`
   (format in CONTRACT §8), commit and push it.
3. Commit (Conventional Commits) **and push** (`git push -u origin <branch>`) every
   logical unit of work, at most ~15 minutes of work per commit. Never leave work
   uncommitted while waiting for a long command.
4. After each completed step, update your progress file, commit and push.
5. Only touch the files you own (CONTRACT §2 "File ownership"). Never edit
   `PROGRESS.md`.
6. If resumed after an interruption: read your progress file, run `git status` and
   `git log --oneline -20`, and continue from "Next action". Never redo `done` tasks.
7. All code, comments, commits and docs in English.
8. Your final answer is at most 10 lines: what you did, branch, last commit hash,
   problems found. Never paste code in it.
