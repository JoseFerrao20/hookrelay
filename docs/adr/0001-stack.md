# 0001. Technology stack

## Status

Accepted

## Date

2026-11-18

## Context

hookrelay is a reliable webhook delivery service: it must accept events, persist
them durably, deliver them to subscriber endpoints with retries, backoff and a
dead-letter queue, and be observable and operable. We need a stack that is
mature, well-supported for messaging and persistence, easy to test against real
infrastructure, and familiar to the maintainer.

## Decision

- **Language/framework:** Java 21 (Temurin) with Spring Boot 3.x.
- **Database:** PostgreSQL 16 for endpoints, events and delivery attempts, with
  Flyway migrations.
- **Broker:** RabbitMQ 3 for the delivery pipeline, retry queues and the DLQ.
  (Retries are scheduled in PostgreSQL instead; see [ADR 0004](0004-retries-and-dead-letters.md).)
- **Build:** Gradle with the Kotlin DSL, using the wrapper, plus Spotless
  (google-java-format) for formatting.
- **Testing:** JUnit 5 and Testcontainers against real PostgreSQL and RabbitMQ.

## Alternatives

| Option | Trade-offs |
|--------|------------|
| Kotlin on Spring Boot | Nicer syntax and null-safety, but adds a second language to learn and tooling complexity for little gain here. |
| Go | Small static binaries and cheap concurrency, but a thinner ecosystem for the data-access and messaging conventions we want, and less familiar to the maintainer. |
| Kafka | Excellent throughput and replay, but per-message retry with backoff and DLQs are not native and need extra topology; operationally heavier than needed. |
| AWS SQS | Managed, with built-in DLQs, but ties the project to a cloud vendor and makes local development and testing harder. |
| Postgres as the queue (e.g. `SKIP LOCKED`) | One fewer component, but delayed retries, fan-out and routing must be hand-built, and queue load competes with the primary database. |
| MySQL | Viable, but PostgreSQL offers richer types (JSONB), stronger transactional DDL and better Testcontainers/Flyway ergonomics for us. |
| Maven | Ubiquitous and declarative, but Gradle's Kotlin DSL gives more flexible, faster builds and better incremental caching. |

## Consequences

- Strong ecosystem support (Spring AMQP, Spring Data, Actuator) shortens delivery.
- Running tests requires a Docker daemon because of Testcontainers.
- Two stateful dependencies (PostgreSQL and RabbitMQ) must be operated and monitored.
- The JVM has a larger memory footprint and slower startup than Go; acceptable
  for a long-running service.
- Gradle builds carry a learning curve, mitigated by the wrapper and CI caching.
