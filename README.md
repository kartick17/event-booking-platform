# Event Booking Platform

A learning project: a small but realistic microservices system built with Java 21 and Spring Boot 3.

Users browse events and book seats. Admins manage events. The point is not the features —
it is everything that happens between the services.

## Services

| Service | Port | Owns |
|---|---|---|
| service-discovery (Eureka) | 8761 | service registry |
| api-gateway | 8080 | routing, edge auth, CORS, rate limiting |
| auth-service | 8081 | users, roles, tokens |
| event-service | 8082 | events, seat capacity |
| booking-service | 8083 | bookings, outbox, idempotency |

Infrastructure (Docker Compose): PostgreSQL, Redis, Kafka.

## Docs

**Start here:** [docs/00-roadmap-and-architecture.md](docs/00-roadmap-and-architecture.md)
**Full index:** [docs/README.md](docs/README.md)

All 16 guides are written — 14 phases, from an empty folder to a saga with compensation.
Each one: concepts first, then build it one piece at a time, then break it on purpose.

## Status

Docs complete. Code: follow the phases in order.
