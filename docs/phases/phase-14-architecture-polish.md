# Phase 14 — Architecture: hexagonal, DDD, CQRS, and the final review

**Time:** 4–5 hours
**Needs:** Phase 13 finished.
**Pre-reading:** the ports-and-adapters diagram; aggregate root in one paragraph.

The system works. This phase steps back, names what you built, refactors one service to make the
structure explicit, and turns the whole project into something you can talk about.

---

# PART A — Concepts

## A1. Layered architecture, and where it leaks

What we have been building:

```
api            controllers, DTOs
   ↓
application    services, use cases
   ↓
domain         entities, business rules
   ↓
infrastructure repositories, Kafka, Feign, Redis
```

**The problem is the last arrow.** `domain` sits above `infrastructure`, so the business rules end up
depending on JPA, Jackson and Spring. Look at our `Event`:

```java
@Entity                      // JPA
@Table(name = "events")      // JPA
public class Event {
    @Id @GeneratedValue      // JPA
    private Long id;
```

**The most important class in the system imports a persistence framework.** Consequences:

- You cannot unit-test it without JPA on the classpath (it happens to work, but by luck).
- Persistence concerns leak into design decisions (`@ManyToOne` versus an id, lazy versus eager).
- Swapping Postgres for something else touches your domain classes.

For a project this size that is an acceptable trade — and **saying "acceptable trade" rather than
"correct" is the point**. Know what you gave up.

## A2. Hexagonal architecture (ports and adapters)

**Invert the dependency: the domain defines interfaces, and infrastructure implements them.**

```
        ┌──────────── driving adapters ────────────┐
        │  REST controller   Kafka consumer   CLI  │
        └───────────────────┬──────────────────────┘
                            │ calls
                  ┌─────────▼──────────┐
                  │   INBOUND PORTS    │  interfaces the app offers
                  │  ReserveSeatsUseCase│
                  ├────────────────────┤
                  │   APPLICATION      │
                  │   + DOMAIN         │  no framework imports
                  ├────────────────────┤
                  │  OUTBOUND PORTS    │  interfaces the app requires
                  │  EventStore        │
                  │  EventPublisher    │
                  └─────────┬──────────┘
                            │ implemented by
        ┌───────────────────▼──────────────────────┐
        │  JPA repository   Kafka producer   Redis │
        └────────────── driven adapters ───────────┘
```

**Every arrow points inward.** The domain knows nothing about HTTP, JPA or Kafka. It declares what it
needs (`EventStore`) and something outside provides it.

**Port** — an interface owned by the inside.
**Adapter** — an implementation owned by the outside.

**What this actually buys you:**

- The domain is testable with plain fakes and no framework.
- A second driving adapter (Kafka consumer alongside REST) needs zero domain changes — **which we have
  already done in Phase 12, informally**.
- Infrastructure choices become replaceable in a way that is real rather than theoretical.

**What it costs:** more interfaces, more mapping between domain objects and JPA entities, more files.
For a CRUD service it is overhead. **For a service with real rules — ours — it earns its keep.**

**We refactor one service to this structure**, so you have felt the difference and can argue either
side.

## A3. DDD vocabulary for what you already built

You have been doing DDD since Phase 4 without the labels. Here they are:

**Entity** — identity that persists through change. `Event` with id 1 is the same event after a rename.

**Value object** — no identity, defined entirely by its values, immutable. `Money(500, "USD")`. Two
equal value objects are interchangeable. **We have none yet**, and `priceCents` as a bare `long` is the
gap.

**Aggregate** — a cluster changed as one unit. **Aggregate root** — the only entry point. `Event` is
both.

**Invariant** — a rule that must always hold. `availableSeats >= 0`. Ours live inside the aggregate,
which is why they cannot be broken.

**Repository** — a collection-like interface for aggregates. One repository per aggregate root, never
one per table.

**Domain event** — something that happened, in the domain's language. `SeatsReserved`.

**Bounded context** — Phase 4, concept A1. One per service here.

**Ubiquitous language** — the same words in code, conversation and documentation. **`reserveSeats`, not
`updateEventCount`.** When the business says "reserve" and the code says "update", every conversation
needs a translation step, and translation is where bugs are born.

### Aggregate design rules

1. **Reference other aggregates by id, never by object.** `Booking` holds `eventId`, not an `Event`.
   Phase 6 forced this because they are in different databases; it is good design even in one.
2. **One transaction changes one aggregate.** Two aggregates atomically means either they are really one
   aggregate, or you need a saga.
3. **Keep aggregates small.** A large aggregate means more contention and more optimistic-lock
   conflicts (Phase 8).

## A4. Domain events versus integration events, one more time

The distinction Phase 10a introduced, now applied concretely:

| | Domain event | Integration event |
|---|---|---|
| Audience | inside this service | other services |
| Shape | rich, your types | flat, primitives, versioned |
| Stability | change freely | **a public contract** |
| Transport | in-memory (`ApplicationEventPublisher`) | Kafka |

```java
// domain event — inside event-service
public record SeatsReservedEvent(Event event, int quantity, String bookingRef) { }

// integration event — published to Kafka
{"eventType":"SeatsReserved","version":1,"payload":{"eventId":1,"quantity":2,...}}
```

**Publishing a domain event directly to Kafka is the same mistake as returning a JPA entity from a
controller.** Everything you emit becomes something you can never change.

Spring's `ApplicationEventPublisher` with `@TransactionalEventListener(phase = AFTER_COMMIT)` is the
clean way to raise domain events from an aggregate and translate them into outbox rows in one place —
worth knowing, and a good refinement to Phase 11.

## A5. CQRS, in proportion

**Command Query Responsibility Segregation:** separate the model you write with from the model you read
with.

**Why:** writes need normalisation, invariants and transactions. Reads need denormalisation, joins and
speed. One model serving both compromises on both.

**Levels, from cheap to expensive:**

1. **Separate methods** — `EventService.reserveSeats()` versus `EventQueryService.listPublished()`.
   Nearly free. **Do this.**
2. **Separate models** — commands use the aggregate; queries use projections or direct SQL, skipping
   the entity entirely. Cheap and often worthwhile.
3. **Separate databases** — a write store and a read store kept in sync by events. Powerful, and now
   you own a synchronisation problem and eventual consistency in your reads.
4. **Event sourcing** — store events as the source of truth and derive state. A different system with a
   different set of problems.

**Levels 3 and 4 are where CQRS gets a bad reputation**, because people adopt them for CRUD
applications. **We are at level 1, moving to level 2 for the list endpoint** — Phase 5's cached list
already reads a DTO and never loads the aggregate, which is exactly the idea.

## A6. Architecture Decision Records

An **ADR** is a short document recording one decision and *why*.

```markdown
# ADR 004: Transactional outbox for event publishing

## Status
Accepted — 2026-08-25

## Context
Business changes and Kafka publishes are two writes to two systems. A crash between them
loses the event silently. 2PC is unavailable (Kafka does not support XA) and blocks.

## Decision
Write events to an `outbox_events` table in the same transaction as the business change.
A polling publisher reads unpublished rows and sends them to Kafka.

## Consequences
+ Guaranteed at-least-once delivery, atomic with the state change.
+ Survives Kafka outages and process crashes.
- Publishing latency up to ~500 ms.
- A new table to maintain, and a poller to monitor.
- Duplicates are possible; consumers must be idempotent.

## Alternatives considered
- Direct publish: rejected — dual write, silent loss.
- 2PC/XA: rejected — unsupported by Kafka, blocking, coordinator SPOF.
- CDC (Debezium): rejected for now — extra infrastructure; revisit if polling load grows.
```

**Write ADRs for decisions that were hard, contested, or will look strange to a newcomer.** Not for
"we used Spring Boot".

**Why it matters:** in a year, someone (probably you) will see the outbox table and ask "why not just
publish?" Without the ADR they will guess, and they may "simplify" it away. **An ADR is how a decision
survives the person who made it** — and being the person who writes them is a genuine seniority signal.

---

# PART B — Do it

## Step 1 — Refactor event-service to ports and adapters

**Target structure:**

```
com.eventbooking.event
├── domain/                        ← no framework imports at all
│   ├── model/
│   │   ├── Event.java             pure domain, no JPA
│   │   ├── EventStatus.java
│   │   ├── Money.java             new value object
│   │   └── SeatCount.java         new value object
│   ├── event/
│   │   └── SeatsReservedEvent.java
│   └── port/
│       ├── in/  ReserveSeatsUseCase.java, ManageEventUseCase.java
│       └── out/ EventStore.java, DomainEventPublisher.java
│
├── application/                   ← implements inbound ports, uses outbound ports
│   └── EventApplicationService.java
│
└── adapter/
    ├── in/
    │   ├── web/    EventController.java
    │   └── kafka/  BookingEventsConsumer.java
    └── out/
        ├── persistence/ EventJpaEntity.java, EventJpaRepository.java, EventStoreAdapter.java
        ├── kafka/       OutboxEventPublisher.java
        └── cache/       RedisEventCache.java
```

**The rule you can check mechanically: nothing in `domain` may import `org.springframework`,
`jakarta.persistence`, or `com.fasterxml`.** That one grep is the whole discipline.

## Step 2 — Value objects

**File:** `.../domain/model/Money.java`

```java
package com.eventbooking.event.domain.model;

public record Money(long cents, String currency) {

    public Money {
        if (cents < 0) {
            throw new IllegalArgumentException("Money cannot be negative");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be a 3-letter code");
        }
    }

    public static Money ofCents(long cents) {
        return new Money(cents, "USD");
    }

    public Money multiply(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        return new Money(cents * quantity, currency);
    }

    public Money add(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot add %s to %s".formatted(other.currency, currency));
        }
        return new Money(cents + other.cents, currency);
    }
}
```

**Compare `Money` with `long priceCents`:**

- `long` lets you add a price to a seat count. **The compiler will not stop you.**
- `long` has no currency, so mixing USD and EUR is silent corruption.
- `long` lets a negative price exist until someone remembers to check.
- **`Money` makes all three impossible.**

The **compact constructor** (`public Money {`) validates before the fields are assigned, so an invalid
`Money` cannot exist. That is the same guarantee as our aggregate constructors, at the level of a single
value.

`multiply` returns a **new** `Money` — value objects are immutable, so they can be shared freely with no
defensive copying and no thread-safety concerns.

**File:** `.../domain/model/SeatCount.java`

```java
package com.eventbooking.event.domain.model;

public record SeatCount(int value) {

    public SeatCount {
        if (value < 0) {
            throw new IllegalArgumentException("Seat count cannot be negative");
        }
    }

    public SeatCount minus(SeatCount other) {
        if (other.value > value) {
            throw new IllegalArgumentException("Cannot reserve more seats than available");
        }
        return new SeatCount(value - other.value);
    }

    public SeatCount plusCappedAt(SeatCount amount, SeatCount cap) {
        return new SeatCount(Math.min(cap.value, value + amount.value));
    }

    public boolean isAtLeast(SeatCount required) {
        return value >= required.value;
    }

    public boolean isZero() {
        return value == 0;
    }
}
```

**`SeatCount` cannot be negative — enforced by the type itself.** Phase 8's oversell drill produced
`available_seats = -7`; with this type, the *code path* that produced it throws at the point of the
mistake rather than persisting a nonsense value.

`plusCappedAt` carries the idempotent-release rule from Phase 4 into the type. **The rule now lives with
the data it constrains**, so nobody has to remember it.

## Step 3 — Ports

**File:** `.../domain/port/out/EventStore.java`

```java
package com.eventbooking.event.domain.port.out;

import com.eventbooking.event.domain.model.Event;

import java.util.List;
import java.util.Optional;

public interface EventStore {

    Optional<Event> findById(Long id);

    Event save(Event event);

    List<Event> findPublished(int page, int size);
}
```

**This interface lives in `domain` and is written in the domain's language.** No `Pageable`, no
`Optional<EventJpaEntity>`, no Spring Data types. **The domain states what it needs; it does not know
who provides it.**

`int page, int size` instead of Spring's `Pageable` looks like a downgrade and is the entire point:
`Pageable` is a Spring type, and a domain that imports Spring is not a domain.

**File:** `.../domain/port/in/ReserveSeatsUseCase.java`

```java
package com.eventbooking.event.domain.port.in;

public interface ReserveSeatsUseCase {

    record ReserveSeatsCommand(Long eventId, int quantity, String bookingRef) { }

    void reserveSeats(ReserveSeatsCommand command);
}
```

**An inbound port is the application's public API, independent of transport.** The REST controller and
the Kafka consumer both call this same method — **which is what we already did in Phase 12, informally.
Now the shape makes it obvious.**

The command is a `record` nested in the interface: it belongs to this use case and nowhere else.

## Step 4 — The domain model without JPA

**File:** `.../domain/model/Event.java`

```java
package com.eventbooking.event.domain.model;

import java.time.Instant;

public class Event {

    private final Long id;
    private String title;
    private String venue;
    private Instant startsAt;
    private final SeatCount capacity;
    private SeatCount availableSeats;
    private Money price;
    private EventStatus status;
    private final String createdBy;
    private Long version;

    public Event(Long id, String title, String venue, Instant startsAt,
                 SeatCount capacity, SeatCount availableSeats, Money price,
                 EventStatus status, String createdBy, Long version) {
        this.id = id;
        this.title = title;
        this.venue = venue;
        this.startsAt = startsAt;
        this.capacity = capacity;
        this.availableSeats = availableSeats;
        this.price = price;
        this.status = status;
        this.createdBy = createdBy;
        this.version = version;
    }

    public static Event create(String title, String venue, Instant startsAt,
                               SeatCount capacity, Money price, String createdBy) {
        if (startsAt.isBefore(Instant.now())) {
            throw new InvalidEventStateException("Event cannot start in the past");
        }
        return new Event(null, title, venue, startsAt, capacity, capacity,
                price, EventStatus.DRAFT, createdBy, null);
    }

    public void reserveSeats(SeatCount quantity) {
        if (!status.acceptsBookings()) {
            throw new InvalidEventStateException("Event is not open for booking: " + status);
        }
        if (!availableSeats.isAtLeast(quantity)) {
            throw new InsufficientSeatsException(id, quantity.value(), availableSeats.value());
        }
        this.availableSeats = availableSeats.minus(quantity);
    }

    public void releaseSeats(SeatCount quantity) {
        this.availableSeats = availableSeats.plusCappedAt(quantity, capacity);
    }

    public Money totalFor(SeatCount quantity) {
        return price.multiply(quantity.value());
    }
}
```

**No annotations. No imports outside `java.*` and your own package.** This class can be unit-tested by a
developer who has never heard of Spring, and it will still compile in ten years when the persistence
framework has changed.

**Two constructors with different jobs:** the public one **reconstitutes** an existing event from
storage (id and version already known); the static `create` factory **makes a new one** and enforces the
creation rules. Reconstitution must not re-run creation validation — an event created last year with
today's rules would fail to load, which is a genuinely painful production bug.

**`reserveSeats` reads almost like English** now that the types carry the concepts:
`if (!availableSeats.isAtLeast(quantity))`. That is the ubiquitous language showing up in the code.

## Step 5 — The persistence adapter

**File:** `.../adapter/out/persistence/EventJpaEntity.java`

```java
@Entity
@Table(name = "events")
public class EventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private String title;
    @Column(nullable = false) private String venue;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(nullable = false) private int capacity;
    @Column(name = "available_seats", nullable = false) private int availableSeats;
    @Column(name = "price_cents", nullable = false) private long priceCents;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private EventStatus status;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Version private Long version;

    // getters, setters, protected no-arg constructor
}
```

**File:** `.../adapter/out/persistence/EventStoreAdapter.java`

```java
@Component
public class EventStoreAdapter implements EventStore {

    private final EventJpaRepository jpaRepository;

    public EventStoreAdapter(EventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Event> findById(Long id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Event save(Event event) {
        return toDomain(jpaRepository.save(toEntity(event)));
    }

    private Event toDomain(EventJpaEntity entity) {
        return new Event(
                entity.getId(), entity.getTitle(), entity.getVenue(), entity.getStartsAt(),
                new SeatCount(entity.getCapacity()), new SeatCount(entity.getAvailableSeats()),
                Money.ofCents(entity.getPriceCents()),
                entity.getStatus(), entity.getCreatedBy(), entity.getVersion());
    }

    private EventJpaEntity toEntity(Event event) {
        EventJpaEntity entity = event.getId() == null
                ? new EventJpaEntity()
                : jpaRepository.findById(event.getId()).orElseGet(EventJpaEntity::new);

        entity.setTitle(event.getTitle());
        entity.setVenue(event.getVenue());
        entity.setStartsAt(event.getStartsAt());
        entity.setCapacity(event.getCapacity().value());
        entity.setAvailableSeats(event.getAvailableSeats().value());
        entity.setPriceCents(event.getPrice().cents());
        entity.setStatus(event.getStatus());
        entity.setCreatedBy(event.getCreatedBy());
        return entity;
    }
}
```

**Two classes representing one concept, and mapping between them by hand. This is the cost of hexagonal
architecture, and it is real.**

**What you get for it:** the JPA entity can change — column names, lazy loading, inheritance strategy —
without touching a line of domain code. The domain can be tested with a `Map`-backed fake `EventStore`.

**What it costs:** two classes to keep in sync, mapping code to write, and a real gotcha in `toEntity`:
loading the existing entity so `@Version` is preserved. **Forget that and every save overwrites the
version, and Phase 8's optimistic locking silently stops working.** A test would catch it (Phase 13);
without one it is invisible until you have concurrent users.

**Be honest about the trade in an interview.** "We used ports and adapters in the service with real
domain logic and plain layering in the CRUD ones" is a better answer than either purist position.

## Step 6 — The application service

```java
@Service
public class EventApplicationService implements ReserveSeatsUseCase, ManageEventUseCase {

    private final EventStore eventStore;
    private final DomainEventPublisher eventPublisher;

    public EventApplicationService(EventStore eventStore, DomainEventPublisher eventPublisher) {
        this.eventStore = eventStore;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void reserveSeats(ReserveSeatsCommand command) {
        Event event = eventStore.findById(command.eventId())
                .orElseThrow(() -> new EventNotFoundException(command.eventId()));

        event.reserveSeats(new SeatCount(command.quantity()));
        eventStore.save(event);

        eventPublisher.publish(new SeatsReservedEvent(
                event.getId(), command.quantity(), command.bookingRef()));
    }
}
```

**Both dependencies are ports — interfaces defined by the domain.** This class has no idea whether
persistence is Postgres or a `HashMap`, or whether publishing means Kafka or a log line.

**`@Transactional` sits here, in the application layer**, which is the right place: the use case defines
the transaction boundary. The domain must not know transactions exist; the adapters must not decide
them.

**The explicit `eventStore.save(event)`** replaces JPA's dirty checking. In a hexagonal design that is
correct — the domain object is not a managed entity, so the use case must say what it wants persisted.
**More typing, and much more obvious**: Phase 4's Drill 5 silent no-op cannot happen here.

## Step 7 — Write the ADRs

**File:** `docs/adr/README.md` plus one file per decision.

Write these six, using the template from concept A6:

| ADR | Decision | The alternative you rejected |
|---|---|---|
| 001 | Database per service | Shared database |
| 002 | Edge authentication with header propagation | Verify JWT in every service |
| 003 | Choreographed saga for booking | Orchestration; 2PC |
| 004 | Transactional outbox | Direct publish; CDC |
| 005 | Atomic UPDATE for seat reservation | Optimistic retry; pessimistic lock |
| 006 | Ports and adapters in event-service only | Everywhere; nowhere |

**Keep each to one page.** The `Consequences` section is the most valuable part, and it must include the
negatives. An ADR that only lists benefits is marketing, and nobody trusts it a year later.

**These six are also your interview script.** Each one is a decision, an alternative, and a trade-off —
exactly the shape of a good architecture answer.

## Step 8 — Final review

Walk the system once more and check each item honestly.

**Boundaries**
- [ ] Each service owns its data; no cross-service foreign keys
- [ ] No shared DTO or entity module between services
- [ ] Each service is deployable alone

**Communication**
- [ ] Sync only where the caller needs the answer to respond
- [ ] Async for facts other services react to
- [ ] Every sync call has a timeout, breaker and fallback
- [ ] Every async message is keyed for ordering and carries a `messageId`

**Data correctness**
- [ ] Invariants live in aggregates
- [ ] Concurrent writes cannot oversell
- [ ] Duplicate requests cannot double-book
- [ ] Duplicate messages cannot double-apply
- [ ] Events are never lost between the database and Kafka

**Failure handling**
- [ ] Any single service can die without taking the platform down
- [ ] Kafka down → outbox queues, delivers later
- [ ] Redis down → reads still work
- [ ] Stuck sagas time out and compensate
- [ ] Failed messages land in a DLT you can see

**Operability**
- [ ] One correlation id traces a request across services and messages
- [ ] Health checks reflect real dependencies
- [ ] Backlog, lag and breaker state are observable
- [ ] Secrets come from the environment, never from Git
- [ ] Migrations are versioned and immutable once applied

## Step 9 — Be able to answer these

The point of the whole project. If you can answer these out loud, you can work on a real multi-service
system.

**Architecture**
1. Why is seat capacity owned by event-service and not booking-service?
2. What would break if you put a foreign key from `bookings` to `events`?
3. When would you merge two services back into one?

**Communication**
4. Why is booking creation async but reading an event sync?
5. What is the availability of an endpoint that synchronously calls two 99% services?
6. Why are our Kafka messages keyed by event id and not booking id?

**Consistency**
7. Why can `@Transactional` not span two services?
8. Explain the dual-write problem and how the outbox solves it.
9. Why is exactly-once delivery impossible, and what do you build instead?
10. What happens if a saga reply arrives after the saga timed out?

**Concurrency**
11. Two users book the last seat at the same millisecond. Walk through what happens.
12. Optimistic versus pessimistic versus atomic update — pick one and defend it.
13. Why did the `CHECK (available_seats >= 0)` constraint not prevent overselling?

**Security**
14. Where is the JWT verified, and why only there?
15. What stops a client sending `X-User-Roles: ROLE_ADMIN`?
16. Why can you not instantly revoke an access token, and what do you do instead?

**Operations**
17. A user says their booking is stuck. How do you investigate?
18. The outbox backlog is 50,000. What do you check, in order?
19. A consumer is being redelivered the same message forever. Why, and what do you do?

**If any answer is shaky, that phase's doc is where to go.** Every one of these is answered somewhere in
`docs/`.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Hexagonal everywhere | Boilerplate with no benefit | Only where domain logic is real |
| Domain importing Spring or JPA | Not actually hexagonal | Grep the domain package |
| Anaemic domain model | Fat services, rules duplicated | Behaviour on the aggregate |
| Primitives for money and counts | Silent unit and currency bugs | Value objects |
| Publishing domain events directly | Internals become a public contract | Map to integration events |
| CQRS with separate databases too early | Sync problems you did not need | Start at level 1 |
| Aggregate holding another aggregate | Huge transactions, more conflicts | Reference by id |
| Re-running creation rules on load | Old data cannot be loaded | Separate factory from reconstitution |
| Losing `@Version` in the mapper | Optimistic locking silently disabled | Load, then update |
| No ADRs | Decisions get "simplified" away | One page each |
| Code and business using different words | Every discussion needs translation | Ubiquitous language |

---

## Production considerations

- **Consistency of structure matters more than purity.** Pick a shape per service type and stick to it.
- **Enforce the domain's independence** with ArchUnit — a test that fails if `domain` imports Spring.
- **ADRs live in the repository**, versioned with the code they describe.
- **Revisit boundaries yearly.** If two services always change together, they may be one service.
- **Value objects for every domain concept with rules** — money, quantities, identifiers, email
  addresses.
- **Keep the ubiquitous language honest.** When the business renames something, rename it in the code.

---

## Phase 14 checklist

- [ ] event-service is restructured into domain / application / adapters
- [ ] Nothing in `domain` imports Spring, JPA or Jackson
- [ ] `Money` and `SeatCount` replace bare primitives
- [ ] Ports are defined by the domain; adapters implement them
- [ ] The mapper preserves `@Version`
- [ ] Six ADRs are written, including their negatives
- [ ] The final review checklist is honestly ticked
- [ ] You can answer all 19 questions out loud
- [ ] You can argue *against* hexagonal architecture as well as for it

---

## You are done

Look back at what the system does.

A user request enters one gateway, is authenticated once at the edge, is routed by a logical service
name resolved through a registry, reaches a service that trusts a header because of a deliberate
network boundary, creates a booking in its own database together with an outbox row in one transaction,
returns `202 Accepted` in 50 milliseconds, and then — asynchronously, durably, idempotently, in order —
reserves seats in a different service with a different database, replies, and confirms the booking.

**If any part of that fails**, the system does not lose data: the outbox retries, the consumer
deduplicates, the breaker sheds load, the lock prevents overselling, the saga times out and compensates,
and the correlation id lets you follow every step.

**That is a real distributed system**, and you built it one deliberate decision at a time.

### Where to go next

- **Observability** — Micrometer, Prometheus, Grafana, OpenTelemetry tracing. You built correlation ids
  by hand; now see the real thing.
- **Deployment** — Docker images, Kubernetes, health probes, rolling deploys. Everything you skipped on
  purpose.
- **Payments** — add a payment service and watch the saga grow to four steps, then convert it to
  orchestration and feel the difference.
- **Scale** — load test, find the hot partition, shard the seat counter, measure.
- **Event sourcing** — the next step past level 2 CQRS, if the domain justifies it.

### And when you are asked about this project

Do not describe the features. Describe the **decisions**: why the boundaries are where they are, why
booking is asynchronous, why the outbox exists, what you gave up to get eventual consistency, and what
you would do differently at ten times the scale.

**That is the difference between someone who has written a microservice and someone who understands
them.**
