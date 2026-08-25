# Docs index

Read in order. Each phase doc is self-contained: concepts first, then build it step by step.

## Start here

- [00 — Roadmap & Architecture](00-roadmap-and-architecture.md)

## Phases

| # | Doc | Status |
|---|---|---|
| 1 | [Foundation: monorepo, Docker infra, Eureka](phases/phase-01-foundation-eureka.md) | written |
| 2a | [Auth: Spring Security architecture + users](phases/phase-02a-auth-security-basics.md) | written |
| 2b | [Auth: JWT, refresh tokens, RBAC](phases/phase-02b-auth-jwt-tokens.md) | written |
| 3 | [API Gateway](phases/phase-03-api-gateway.md) | written |
| 4 | [Event Service](phases/phase-04-event-service.md) | written |
| 5 | [Redis caching](phases/phase-05-redis-caching.md) | written |
| 6 | [Booking Service + OpenFeign](phases/phase-06-booking-openfeign.md) | written |
| 7 | [Resilience4j](phases/phase-07-resilience4j.md) | written |
| 8 | [Concurrency & locking](phases/phase-08-concurrency-locking.md) | written |
| 9 | [Idempotency](phases/phase-09-idempotency.md) | written |
| 10a | [Kafka concepts](phases/phase-10a-kafka-concepts.md) | written |
| 10b | [Kafka implementation](phases/phase-10b-kafka-implementation.md) | written |
| 11 | [Transactional Outbox](phases/phase-11-outbox-pattern.md) | written |
| 12 | [Saga & failure handling](phases/phase-12-saga-failures.md) | written |
| 13 | [Testing](phases/phase-13-testing.md) | written |
| 14 | [Architecture polish](phases/phase-14-architecture-polish.md) | written |

## Concept deep-dives

Linked from the phases where they first matter.

| Doc | First needed in |
|---|---|
| [Filters, interceptors and where each one runs](concepts/filters-and-request-lifecycle.md) | Phase 2a |
| [application.yml vs application.properties](concepts/yaml-vs-properties.md) | Phase 1 |

## Status

**All 16 docs written.** Roadmap, 2 concept deep-dives, 14 phase docs (phases 2 and 10 are split in two).

## How to use these docs

Every phase doc has the same shape:

- **Part A — Concepts.** Read before typing. What it is, why it exists, how it works inside.
- **Part B — Build it.** One coherent piece of code at a time, with the reason it exists and what it connects to.
- **Break it on purpose.** Failure drills. Not optional — this is where distributed systems are actually learned.
- **Common mistakes / Production considerations / Checklist.**
