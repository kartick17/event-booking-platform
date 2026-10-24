# Phase 14 — Architecture: hexagonal, DDD, CQRS, and the final review

**Time:** 4–5 hours
**Needs:** Phase 13 finished.
**Pre-reading:** the ports-and-adapters diagram; aggregate root in one paragraph.

The system works. Now you will make one service's structure clearer, learn names for the design choices already used, and write down the reasons for those choices. This also helps you explain the project to someone else.

---

## In plain English

This phase makes the working code easier to organise, test, and explain. You will separate business rules from details such as HTTP, database storage, and Kafka. You will also record why important design choices were made.

Example: the seat rule should be understandable without knowing how a database row is saved. The business code asks a storage interface to save an event. A separate implementation handles Postgres. Tests can supply a small in-memory implementation instead.

Words used in this phase:

- **Architecture:** how parts of the application are arranged and depend on each other.
- **Refactor:** change code structure while keeping the intended behaviour.
- **Domain:** the business objects and rules, such as events and seat reservations.
- **Infrastructure:** tools and code for storage, networking, and messaging.
- **Port:** an interface describing a way to use the application or a capability it needs.
- **Adapter:** code connecting a port to a particular tool, such as a database.
- **Hexagonal architecture:** organising code so business rules sit inside and tools connect through ports and adapters.
- **Value object:** a value with its own checks, such as an amount and currency; it has no separate database identity.
- **DDD:** Domain-Driven Design; organising code around business meanings and rules.
- **CQRS:** separating how data is changed from how data is read.
- **ADR:** a short written record of a design decision and its reasons.

Start by identifying which code decides business rules and which code talks to tools. Then follow the refactor steps for one service.

---

# PART A — Understand the ideas

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

**Look at the dependency on storage tools.** Our business objects currently use JPA annotations, so their source code knows about the persistence framework. Here is `Event`:

```java
@Entity                      // JPA
@Table(name = "events")      // JPA
public class Event {
    @Id @GeneratedValue      // JPA
    private Long id;
```

**The business object depends on a database library.** This has some costs:

- Tests need JPA classes available even when they do not use a real database.
- Database features can influence the business model, such as object relationships and lazy loading.
- Changing the storage approach may require changes to business classes too.

For a small project, keeping JPA annotations in the model can be a reasonable simplification. Understand the cost rather than assuming one structure is always right.

## A2. Hexagonal architecture (ports and adapters)

**Let the inside define what it needs.** Business code defines an interface for storage or another capability. Code on the outside implements that interface using the chosen tool.

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

**The business code does not import HTTP, JPA, or Kafka classes.** It uses an interface such as `EventStore`. A separate implementation connects that interface to the real database.

**Port:** an interface for using the application or providing something it needs.
**Adapter:** code that connects the interface to a specific tool or entry point.

**What this actually buys you:**

- Business rules can be tested with a simple fake implementation, without starting a framework.
- HTTP controllers and Kafka consumers can both call the same application logic. Phase 12 already used this idea.
- A different storage or messaging implementation can be added behind the interface without changing the business rules.

**The cost is more code:** interfaces, separate database entities, and conversion between objects. A simple create/read/update/delete service may not need that extra structure. It becomes more useful when business rules are substantial.

**Refactor one service in this phase.** Then compare it with the earlier structure and explain where each approach is useful.

## A3. DDD vocabulary for what you already built

**DDD means Domain-Driven Design.** It gives names to ways of keeping code close to business meanings. You have already used several of these ideas since Phase 4:

**Entity:** an object identified by its id even when its details change. Event 1 is still the same event after a title change.

**Value object:** an object defined by its values, without a separate identity. `Money(500, "USD")` can hold an amount, currency, and checks. Two Money objects with the same values mean the same thing. We have not added one yet; the price is still a plain `long`.

**Aggregate:** related data changed together. **Aggregate root:** the main object used to make those changes and check the rules. In our current model, `Event` fills both roles.

**Invariant:** a rule that must stay true, such as `availableSeats >= 0`. The Event object checks these rules when its methods change data.

**Repository:** an interface used to load and save aggregates. Design it around the main business object, rather than adding one repository for every database table automatically.

**Domain event:** a fact about the business, such as `SeatsReserved`.

**Bounded context:** an area with its own meaning of data and its own rules, as described in Phase 4. Each service is one such area in this project.

**Ubiquitous language:** use the same business words in conversations, docs, and code. For example, `reserveSeats` says what the action means more clearly than `updateEventCount`.

### Aggregate design rules

1. **Refer to other aggregates by id.** `Booking` holds `eventId`, not an `Event` object. That also works across separate databases.
2. **Keep each local transaction focused.** A workflow across services needs separate transactions and a recovery plan, such as a saga.
3. **Keep aggregates small.** Large aggregates cause more requests to compete for the same updates and create more version conflicts.

## A4. Domain events versus integration events, one more time

The distinction Phase 10a introduced, now applied with an example:

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

Publishing an internal domain object makes its structure part of another service's expectations. Use a separate integration message format so internal refactoring does not unexpectedly break consumers.

`ApplicationEventPublisher` and `@TransactionalEventListener` can handle internal events around transaction boundaries. Pay attention to `phase = AFTER_COMMIT`: it runs after the original transaction commits. It does not by itself make a later outbox insert atomic with that earlier change. Phase 11's rule still applies: the outbox row and business change must commit together.

## A5. CQRS, in proportion

**CQRS = Command Query Responsibility Segregation.** In simple words, separate the code and models used to change data from those used to read it.

**Why separate them?** Writes must protect rules and save correct changes. Reads may need a convenient view combining or copying fields. The same object shape is not always best for both jobs.

**Levels, from cheap to expensive:**

1. **Separate methods:** for example, `EventService.reserveSeats()` for a change and `EventQueryService.listPublished()` for a read. This is the simplest starting point.
2. **Separate models:** writes use the business object; reads use DTOs or selected SQL fields without loading the whole object.
3. **Separate databases:** changes go to one database, while messages update another database used for reads. This adds delayed agreement and recovery work.
4. **Event sourcing:** store the history of changes as events and build current views from that history. This adds more design and operating work.

**Do not jump straight to separate databases or event sourcing.** This project starts with separate read and write methods and moves towards separate read models. A cached list response is already an example of a read-focused view.

## A6. Architecture Decision Records

**ADR = Architecture Decision Record.** It is a short note explaining what you chose, why you chose it, and what alternatives you considered.

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

**Write ADRs for choices a new reader might question.** For example, explain why an outbox is needed rather than only writing "we use Spring Boot."

Later, someone may see the outbox table and ask why messages are not sent directly. An ADR preserves the failure case and reasoning, so a cleanup does not accidentally remove an important protection.

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

**Check the dependency rule:** files in `domain` must not import `org.springframework`, `jakarta.persistence`, or `com.fasterxml`. This keeps the business code separate from these tools.

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

`multiply` returns a **new** `Money` — value objects are fixed after creation, so they can be shared freely with no
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

**`SeatCount` rejects negative values when created.** Instead of carrying an invalid value further into the application, the code fails at the place that tried to create it. Database constraints still protect writes made outside this object.

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

**An inbound port is an interface callers use to run application actions.** Both an HTTP controller and a Kafka consumer can call it. The interface does not need to know which transport brought the request.

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

**This class uses only `java.*` and our own code.** It has no Spring or JPA annotations, so its business rules can be tested without those frameworks. Database and HTTP code live outside it.

**There are two ways to build the object.** The public constructor restores an event already saved in the database, including its id and version. The static `create` method makes a new event and checks creation rules. Loading an old event must not reject it just because a rule for new events changed later.

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

**The extra work is keeping two representations and mapping between them.** In `toEntity`, preserve the existing entity's `@Version`. Losing or resetting that value can break the competing-update protection from Phase 8. Add a test for the mapping and version behaviour.

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

**Call `eventStore.save(event)` to save changes.** This domain object is not a JPA-managed entity, so Hibernate is not tracking its field changes. The application must request the save rather than rely on dirty checking.

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
- [ ] Happening at the same time writes cannot oversell
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
- [ ] Migrations are versioned and fixed after creation once applied

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
| Publishing domain events directly | Internal details become a public contract | Map to integration events |
| CQRS with separate databases too early | Sync problems you did not need | Start at level 1 |
| Aggregate holding another aggregate | Huge transactions, more conflicts | Reference by id |
| Re-running creation rules on load | Old data cannot be loaded | Separate factory from reconstitution |
| Losing `@Version` in the mapper | Optimistic locking without a clear error disabled | Load, then update |
| No ADRs | Decisions get "simplified" away | One page each |
| Code and business using different words | Every discussion needs translation | Ubiquitous language |

---

## Notes for a real running system

- **Use a consistent structure** so readers can find the same kinds of code in each service.
- **Check dependency rules with ArchUnit**, for example rejecting a Spring import inside `domain`.
- **Keep ADRs in the repository** beside the code they explain.
- **Review service boundaries over time.** Services that always change together may need a different boundary.
- **Use value objects where values have rules**, such as money, quantities, identifiers, and email addresses.
- **Keep business names consistent.** If the business changes a term, review the names used in code too.

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

A client sends a request to the gateway. The gateway checks the token and finds the target service through Eureka. That service trusts the forwarded user headers only under the network protection described earlier. Booking-service saves a pending booking and an outbox message in one transaction, then returns `202 Accepted`. Later, messages ask event-service to handle the seats. Its reply lets booking-service confirm or reject the booking. Saved outgoing messages and duplicate checks help this continue safely after failures.

**If any part of that fails**, the system does not lose data: the outbox retries, the consumer
deduplicates, the breaker sheds load, the lock prevents overselling, the saga times out and compensates,
and the correlation id lets you follow every step.

**That is a real distributed system**, and you built it one deliberate decision at a time.

### Where to go next

- **Observability:** tools for understanding the running system through logs, measurements, and traces. Explore Micrometer, Prometheus, Grafana, and OpenTelemetry after the correlation-id work.
- **Deployment:** packaging and running services using Docker images, Kubernetes, health checks, and gradual updates.
- **Payments:** add another service and consider whether a central saga coordinator makes the longer flow easier to manage.
- **Scale:** measure under load, find the busiest partition or seat counter, and address the measured limit.
- **Event sourcing:** consider storing a history of changes if the business needs justify its added complexity.

### And when you are asked about this project

Do not describe the features. Describe the **decisions**: why the boundaries are where they are, why
booking is asynchronous, why the outbox exists, what you gave up to get eventual consistency, and what
you would do differently at ten times the scale.

**That is the difference between someone who has written a microservice and someone who understands
them.**
