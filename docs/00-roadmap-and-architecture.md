# Phase 0 — Roadmap & Architecture

Read this once fully. Everything later refers back to it.

---

## 1. What we are building

An **Event Booking Platform**. Users sign up, browse events, and book seats.
Admins create and manage events.

Small in features. Realistic in structure. Every hard part of microservices shows up here:

| Real-world problem | Where it shows up in our project |
|---|---|
| Who is this user? | Auth Service + JWT + Gateway |
| Where is the other service? | Eureka discovery |
| Service A needs data from B | Booking → Event (OpenFeign) |
| The other service is down | Resilience4j |
| Two users book the last seat | Booking + Event locking |
| One database transaction is not enough | Saga + Outbox |
| Reads are slow | Redis cache |
| Message arrives twice | Idempotent consumers |

---

## 2. Architecture

### 2.1 High level

```
                          ┌──────────────┐
                          │    Client    │
                          │ (Postman/UI) │
                          └──────┬───────┘
                                 │ HTTPS + Bearer JWT
                                 ▼
                       ┌─────────────────────┐
                       │    API Gateway      │  Spring Cloud Gateway
                       │  - routing          │
                       │  - JWT check        │
                       │  - CORS             │
                       │  - correlation id   │
                       └──────────┬──────────┘
                                  │ asks "where is event-service?"
                                  ▼
                       ┌─────────────────────┐
                       │ Service Discovery   │  Eureka Server
                       │ (service registry)  │
                       └─────────────────────┘
                                  ▲
              all services register + heartbeat here
                                  │
        ┌─────────────────┬───────┴────────┬──────────────────┐
        ▼                 ▼                ▼                  │
┌───────────────┐ ┌───────────────┐ ┌────────────────┐        │
│ Auth Service  │ │ Event Service │ │ Booking Service│────────┘
│               │ │               │ │                │
│ users, roles  │ │ events, seats │ │ bookings       │
│ JWT issue     │ │ Redis cache   │ │ outbox table   │
└───────┬───────┘ └───────┬───────┘ └────────┬───────┘
        │                 │                  │
        ▼                 ▼                  ▼
  ┌──────────┐      ┌──────────┐       ┌──────────┐
  │ auth_db  │      │ event_db │       │booking_db│   separate schemas
  └──────────┘      └──────────┘       └──────────┘
                          ▲                  │
                          │                  │
                    ┌─────┴──────────────────┴─────┐
                    │        Apache Kafka          │
                    │  event.events / booking.events│
                    └──────────────────────────────┘
```

### 2.2 Two communication paths (this is the key idea)

**Synchronous (REST via OpenFeign)** — "I need an answer right now to reply to the user."
```
Booking Service ──GET /api/v1/events/{id}──▶ Event Service
                ◀────── event details ──────
```
Used only for *reads* and fast validation. Caller waits. Caller can fail.

**Asynchronous (Kafka)** — "Something happened. Whoever cares, react later."
```
Booking Service ──BookingCreated──▶ Kafka ──▶ Event Service (reserve seats)
Event Service   ──SeatsReserved ──▶ Kafka ──▶ Booking Service (confirm booking)
Event Service   ──SeatsRejected ──▶ Kafka ──▶ Booking Service (reject booking)
```
Nobody waits. Nobody blocks. This is how we get **eventual consistency** and how the Saga works.

### 2.3 The booking flow we are aiming for (final target)

```
1. POST /api/v1/bookings          → Booking Service
2. Booking Service                 → quick sanity check via Feign (event exists, not cancelled)
3. Booking saved as PENDING        + outbox row  (ONE local DB transaction)
4. Outbox poller                   → publishes BookingCreated to Kafka
5. Event Service consumes          → tries to reserve seats with optimistic lock
6a. success → publishes SeatsReserved  → Booking Service marks CONFIRMED
6b. no seats → publishes SeatsRejected → Booking Service marks REJECTED  (compensation)
```

**Why not just call Event Service directly and decrement seats?**
Because that is a distributed transaction across two databases. `@Transactional` cannot cover it.
If Booking commits and Event's HTTP call times out, you do not know if seats were taken or not.
The Saga above never lies — every step is a local transaction plus a durable event.

Booking is `PENDING` for a few hundred milliseconds. That is eventual consistency, and it is fine.

---

## 3. Services and responsibilities

Rule we follow: **one service owns one bounded context and its data. Nobody else touches its tables.**

### Auth Service — "who are you"
- Register, login, refresh token, logout
- BCrypt password hashing
- Issues JWT access token (short life) + refresh token (long life)
- Owns: `users`, `roles`, `refresh_tokens`
- Roles: `USER`, `ADMIN`
- **Not** in scope: it does not know what an event or a booking is.

### Event Service — "what can be booked"
- Admin CRUD on events
- Owns capacity, `available_seats`, event status (`DRAFT`, `PUBLISHED`, `CANCELLED`, `COMPLETED`)
- Redis cache for event reads
- Consumes `BookingCreated` → reserves/releases seats
- Publishes `EventCreated`, `EventUpdated`, `SeatsReserved`, `SeatsRejected`
- Owns: `events`, `processed_messages`
- **Seat count lives here.** Single owner = no double booking.

### Booking Service — "who booked what"
- Create booking, cancel booking, get booking, list my bookings
- Idempotency keys so a retried POST does not create two bookings
- Transactional outbox
- Consumes `SeatsReserved` / `SeatsRejected`
- Publishes `BookingCreated`, `BookingCancelled`
- Owns: `bookings`, `outbox_events`, `idempotency_keys`, `processed_messages`
- **Not** in scope: it never writes to `events`.

### API Gateway — the front door
- Single entry point for the client
- Routes `/api/v1/auth/**` → auth-service, etc.
- Validates the JWT signature and expiry once, at the edge
- Puts `X-User-Id` / `X-User-Roles` headers on the forwarded request
- CORS, rate limiting, correlation ID
- **Not** in scope: no business logic, no database.

### Service Discovery (Eureka Server)
- A phone book. Services register their name + host + port and send a heartbeat.
- Gateway and Feign clients look up `lb://event-service` instead of a hardcoded `http://localhost:8082`.
- Gives us client-side load balancing for free.

---

## 4. Technology choices

Locked in, as you asked:

| Layer | Choice | Why |
|---|---|---|
| Language / runtime | Java 21 | Records, pattern matching, virtual threads available |
| Framework | Spring Boot 3.x | Current baseline |
| Cloud stack | Spring Cloud 2023.x/2024.x | Gateway, Eureka, OpenFeign, LoadBalancer |
| Security | Spring Security 6 + JWT | You want the filter chain internals |
| DB | PostgreSQL 16 | One database, one schema per service |
| ORM | Spring Data JPA / Hibernate | Already known |
| Cache | Redis 7 | Cache-aside + distributed lock later |
| Messaging | Apache Kafka | Event-driven core |
| Resilience | Resilience4j | Retry, circuit breaker, bulkhead |
| Tests | JUnit 5, Mockito, Spring Boot Test, Testcontainers | Real integration tests |
| Build | Maven multi-module | Monorepo |
| Local infra | Docker Compose | Postgres, Redis, Kafka |

### 4.1 Decisions taken (Phase 0)

All four confirmed. Locked in.

**(a) DB migrations — Flyway. → YES, adding Flyway.**
You listed "database migrations" as a must-learn but no tool. `ddl-auto=update` is dangerous in production and hides schema changes. Flyway = numbered SQL files, versioned, repeatable, works fine with Testcontainers.
*Recommendation:* add Flyway. Small, one dependency, teaches real schema ownership.

**(b) OpenFeign vs Spring 6 `@HttpExchange` + `RestClient`. → OpenFeign, with `@HttpExchange` shown side by side.**
Spring Cloud OpenFeign is now feature-frozen (maintenance only). The modern replacement is Spring's own declarative HTTP interface: same idea, same annotations-on-an-interface feel, but built into Spring Framework.
*Recommendation:* still use **OpenFeign** for this project. Reason: every real 8–10 service Spring shop you will join today runs Feign, and you said the goal is reading real codebases. I will show `@HttpExchange` side by side in one section so you know both.

**(c) JWT filter. → Hand-write `OncePerRequestFilter` first, starter version shown later.**
Production teams usually use the OAuth2 resource server starter — it validates JWTs for you, no custom filter.
*Recommendation:* **hand-write** the `OncePerRequestFilter` in Phase 2, because your whole learning goal is understanding the Spring Security filter chain. Then in a later phase I will show the 6-line starter version and what it replaced.

**(d) Concept notes. → `docs/` in this repo, numbered.**
Your existing habit saves tutor notes as numbered files in `jdbc/`. For this project I suggest `docs/` inside this repo, so notes sit next to the code they explain.

---

## 5. Repository structure

```
event-booking-platform/
├── pom.xml                        # parent POM: java version, dependency versions
│
├── services/
│   ├── auth-service/
│   ├── event-service/
│   └── booking-service/
│
├── gateway/
│   └── api-gateway/
│
├── discovery/
│   └── service-discovery/
│
├── infrastructure/
│   ├── docker-compose.yml         # postgres, redis, kafka
│   └── init/                      # db init scripts
│
├── docs/
│   ├── 00-roadmap-and-architecture.md
│   ├── 01-... (one note per concept, as we go)
│   └── adr/                       # architecture decision records
│
└── README.md
```

### 5.1 One change I recommend to your proposed structure

Add a **parent `pom.xml` at the root** that lists all five modules.
Why: one place for the Java version and Spring Boot/Spring Cloud versions. One `mvn clean install` builds everything. Without it you have five unrelated projects that happen to share a folder — a monorepo in name only.

### 5.2 One thing I recommend AGAINST

Do **not** create a `shared-dtos` or `common-lib` module for DTOs shared between services.
It feels efficient. It is the most common microservices mistake. It couples services at compile time — change one DTO and three services must be rebuilt and redeployed together. That is a distributed monolith.
Small duplication between services is correct. A `common` module for pure technical helpers (correlation-id filter, error response shape) is acceptable later, and I will flag exactly when.

### 5.3 Package structure inside each service

```
com.eventbooking.event
├── api/            controllers, request/response DTOs
├── domain/         entities, value objects, domain logic, domain events
├── application/    services, use cases, mappers
├── infrastructure/ repositories, kafka producers/consumers, feign clients, redis
├── config/         SecurityConfig, KafkaConfig, RedisConfig
└── exception/      custom exceptions + @RestControllerAdvice
```
Layer rule: `api` → `application` → `domain`; `infrastructure` implements interfaces the inner layers declare. That is the practical version of hexagonal architecture. We start layered, and refactor one service to ports/adapters near the end so you feel the difference.

---

## 6. Development phases

Each phase = **teach the concept → show the flow → design decisions → small plan → implement → you verify → next.**

| # | Phase | Main concepts | Est. time |
|---|---|---|---|
| 0 | Roadmap & architecture (this doc) | monolith vs microservices, bounded context, service boundaries, DB per service | 1 h |
| 1 | Monorepo skeleton + Eureka + Docker Compose | service registry, registration, heartbeat, health checks, client-side load balancing | 3–4 h |
| 2 | Auth Service | Spring Security filter chain, `AuthenticationManager`, `UserDetailsService`, `PasswordEncoder`, JWT access + refresh, RBAC, `SecurityContext`, Flyway | 8–10 h |
| 3 | API Gateway | routing, gateway filters vs servlet filters, edge auth, header propagation, CORS, correlation ID | 5–6 h |
| 4 | Event Service (CRUD + contracts) | DTO/validation, global exception handling, API versioning, transaction boundaries, aggregates | 6–7 h |
| 5 | Redis caching | cache-aside, TTL, invalidation, stale reads, cache stampede, Redis down | 4–5 h |
| 6 | Booking Service + OpenFeign | sync inter-service calls, API contracts, timeouts, error mapping, REST vs messaging | 6–7 h |
| 7 | Resilience4j | timeout, retry, exponential backoff, circuit breaker, fallback, bulkhead, rate limiter, cascading failure | 5–6 h |
| 8 | Concurrency & double booking | race conditions, optimistic vs pessimistic locking, `@Version`, `SELECT FOR UPDATE`, Redis distributed lock | 5–6 h |
| 9 | Idempotency | idempotency keys, duplicate POST, retry-safe writes | 3–4 h |
| 10 | Kafka fundamentals + first events | broker, topic, partition, offset, consumer group, keys, ordering, acks, retention | 7–8 h |
| 11 | Transactional Outbox | dual-write problem, outbox table, poller, at-least-once, duplicates | 5–6 h |
| 12 | Saga + failure handling | choreography vs orchestration, compensating transactions, retry, DLT, idempotent consumers, eventual consistency | 7–8 h |
| 13 | Testing | Mockito, slice tests, MockMvc, Testcontainers (Postgres/Kafka/Redis), contract testing concepts | 7–8 h |
| 14 | Architecture polish | hexagonal, DDD aggregates, domain vs integration events, CQRS basics, ADRs | 4–5 h |

**Total: roughly 75–95 hours of focused work.**
At 2 h/day → about 6–7 weeks. At weekends only → about 3 months.
Phases 10–12 are the hardest and the highest value. Do not rush them.

### 6.1 Failure drills

Every phase from 5 onward ends with a **break-it drill**: we kill a container and watch what happens.

| Phase | Drill |
|---|---|
| 3 | Kill auth-service, call a protected route. Does the gateway still work? |
| 5 | Kill Redis. Does event-service still serve reads? |
| 6 | Kill event-service. What does booking-service return? How fast? |
| 7 | Same, but with the circuit breaker on. Compare response times. |
| 8 | Two curl requests for the last seat at the same time. |
| 9 | Send the same POST twice. Count the bookings. |
| 11 | Kill the app right after the DB commit, before Kafka publish. |
| 12 | Make the consumer throw. Watch retries, then the dead letter topic. |

---

## 7. What to learn before each phase

Short reading, not courses. Do this the evening before.

| Before phase | Learn / skim |
|---|---|
| 1 | What a service registry is; why hardcoded hosts break; Maven multi-module basics |
| 2 | HTTP `Authorization: Bearer`; what a JWT is made of (header.payload.signature); hashing vs encryption |
| 3 | Reverse proxy idea; difference between "auth at the edge" and "auth in each service" |
| 4 | Bounded context; why DTOs exist and why entities must not leak out of the controller |
| 5 | Cache hit/miss; TTL; why cache invalidation is called one of the two hard problems |
| 6 | Synchronous vs asynchronous calls; what a timeout actually is at the socket level |
| 7 | Cascading failure; the "thread pool exhaustion" story behind circuit breakers |
| 8 | ACID isolation levels; lost update problem |
| 9 | HTTP method semantics: which are naturally idempotent, and why POST is not |
| 10 | Log-based messaging vs queue-based (Kafka vs RabbitMQ) in one paragraph |
| 11 | The dual-write problem: DB commit + message send cannot be atomic |
| 12 | CAP theorem in plain terms; what "eventual consistency" means to a user |
| 13 | Test pyramid; why Testcontainers beats H2 for integration tests |
| 14 | Ports and adapters diagram; aggregate root in one paragraph |

I will teach each of these properly when we reach it. The pre-reading only makes my explanation land faster.

---

## 8. Ground rules for our sessions

1. I explain before I code. Always.
2. One small step at a time. You confirm you understand, then we continue.
3. Ask "why?" as often as you like. You get the concept, not more code.
4. If you propose a design that will hurt later, I say so and explain why.
5. Where there are several valid options, you get a comparison and one clear recommendation.
6. No clever patterns for the sake of looking clever. The project stays small enough to hold in your head.

---

## 9. Next step

Decisions are made. Next we start **Phase 1**:
Maven parent POM, Docker Compose infrastructure, Eureka server, and one service registering itself — so you can watch a service appear in the Eureka dashboard and understand exactly what registration and heartbeat mean.
