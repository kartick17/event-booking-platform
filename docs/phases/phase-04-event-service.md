# Phase 4 — Event Service: bounded context, aggregates, API contracts

**Time:** 6–7 hours
**Needs:** Phase 3 finished (gateway routing and stripping headers).
**Pre-reading:** bounded context; why entities must not leak out of a controller.

This is the first service that is purely business. It is also where you learn to design a service
whose rules cannot be broken from outside — which is what makes Phase 8's concurrency work possible.

---

# PART A — Concepts

## A1. Bounded context — the idea the whole architecture rests on

A **bounded context** is a boundary inside which words mean exactly one thing.

Take the word "event":

- To **event-service**, an Event is a concert with a venue, a date, and 500 seats.
- To **booking-service**, an "event" is barely more than an id and a price.
- To a marketing service, it would be a campaign target with an audience segment.

**One word, three meanings.** The classic mistake is to build one giant shared `Event` class that
serves all three. It ends up with 40 fields, every team must agree before changing it, and nobody can
deploy alone. That is a distributed monolith wearing microservice clothes.

The fix: **each context owns its own model.** Booking-service keeps `eventId` and its own snapshot of
what it needs. When contexts must exchange data, they exchange a **contract** (a DTO or an event
schema), not a class.

> **Interview definition:** "A bounded context is the boundary within which a model is valid. Two
> contexts can use the same word for different things, and that's fine — what crosses the boundary is a
> contract, never a shared domain class."

### Domain, subdomain, context

- **Domain** — the whole business: event booking.
- **Subdomains** — identity, catalogue, booking, payment.
- **Bounded contexts** — the software boundaries you actually build. Usually one per subdomain, and in
  our system one per service.

### How to tell a boundary is wrong

You have drawn the line in the wrong place if:

- One user action always requires changing two services together.
- One service reads another's database.
- Two services keep the same data and must agree instantly.
- One service is only ever a thin proxy over another.

**Our boundaries:** auth owns identity, event owns catalogue **and seat capacity**, booking owns
bookings. Seat capacity living in event-service is the decision that makes everything else work — one
owner of the number means no distributed lock is needed to change it.

## A2. Aggregate and aggregate root

An **aggregate** is a cluster of objects treated as one unit for changes. The **aggregate root** is the
one object outside code is allowed to touch.

Our `Event` is an aggregate root, and its invariants — the rules that must *always* hold — are:

1. `availableSeats` is never negative.
2. `availableSeats` never exceeds `capacity`.
3. A `CANCELLED` event never accepts a reservation.
4. Status changes follow the allowed transitions.

**Why this matters practically:** if these rules live in a service class, every new caller must
remember them, and one that forgets creates corrupt data that outlives the bug. If they live *inside
the entity*, breaking them is impossible without editing the entity.

```java
// Rules live outside the object — every caller must remember them
if (event.getAvailableSeats() >= qty && event.getStatus() == PUBLISHED) {
    event.setAvailableSeats(event.getAvailableSeats() - qty);   // and one caller will forget
}

// Rules live inside the object — impossible to get wrong
event.reserveSeats(qty);   // throws if it would break an invariant
```

The second version is the aggregate root doing its job. **No setters on the entity** is not style
preference — it is what makes the guarantee real.

> **Interview definition:** "An aggregate is a consistency boundary. Everything inside it is updated in
> one transaction and its invariants always hold; things outside reference it by id only."

### One aggregate, one transaction

The rule of thumb: **one transaction changes one aggregate.** If you find yourself needing to change
two aggregates atomically, either they are really one aggregate, or you need eventual consistency
between them — which is exactly the Saga in Phase 12.

Here, `Event` and `Booking` live in different services and different databases. They *cannot* be
changed in one transaction. That constraint is not an inconvenience; it is the reason the rest of this
project exists.

## A3. State machines beat boolean flags

Our event status: `DRAFT → PUBLISHED → CANCELLED / COMPLETED`.

The lazy version is a few booleans — `isPublished`, `isCancelled`, `isCompleted`. Then someone sets two
of them and you have an event that is both published and cancelled, and no code knows what that means.

An **enum plus explicit allowed transitions** makes the invalid states unrepresentable:

```
DRAFT ──publish──▶ PUBLISHED ──complete──▶ COMPLETED
  │                     │
  └──cancel──▶ CANCELLED ◀──cancel──┘
```

`COMPLETED → PUBLISHED` is not on the diagram, so it cannot happen. Encode the arrows in code and every
caller inherits the rule for free.

## A4. API contracts and backward compatibility

Your API is a **promise**. Once a client depends on it, breaking it breaks them.

**Safe (backward compatible):**
- adding a new optional field to a response
- adding a new optional field to a request
- adding a new endpoint
- adding a new enum value **that old clients will never receive**

**Breaking:**
- removing or renaming a field
- changing a type (`String` → `Integer`)
- making an optional field required
- changing the meaning of a field while keeping its name — the worst kind, because nothing fails
  loudly
- adding an enum value old clients *will* receive and cannot parse

**The rule: additive changes only.** When you genuinely must break something, that is `/api/v2/`, run
side by side, with a migration window and a deprecation notice.

**Pagination is part of this.** `GET /api/v1/events` returning every row works with 50 events and takes
your service down with 500,000. Paginate from the first day — retrofitting it is a breaking change.

## A5. Trusting the gateway's headers

Event-service will **not** verify JWTs. It reads `X-User-Id` and `X-User-Roles`, which the gateway set
after stripping any client-supplied copies (Phase 3, concept A4).

We still build a Spring Security `Authentication` from those headers, because that gives us
`@PreAuthorize`, `SecurityContextHolder`, and the same programming model as auth-service — with none
of the key handling.

```
gateway (verifies JWT, sets headers)
   │
   ▼
event-service
   HeaderAuthenticationFilter  ← reads X-User-Id / X-User-Roles
        → builds Authentication
        → SecurityContextHolder
             → @PreAuthorize("hasRole('ADMIN')") works exactly as before
```

**Say the trade-off out loud, because an interviewer will ask:** this is safe only while services are
unreachable from outside the cluster. The moment event-service is exposed, anyone can send
`X-User-Roles: ROLE_ADMIN`. In production: private subnets, security groups, or mTLS in a service mesh.

---

# PART B — Build it

## Step 1 — The module

**File:** `services/event-service/pom.xml`

Same parent block as the others. Dependencies:

```xml
    <artifactId>event-service</artifactId>
    <name>event-service</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
```

**No JWT libraries.** Concept A5 — this service never parses a token. That is the practical payoff of
edge authentication: the key does not spread.

`starter-security` is still here, for the filter chain and `@PreAuthorize`.

Add the module to the root POM.

## Step 2 — Configuration

**File:** `services/event-service/src/main/resources/application.yml`

```yaml
server:
  port: 8082

spring:
  application:
    name: event-service

  datasource:
    url: jdbc:postgresql://localhost:5432/event_db
    username: ebp
    password: ebp_password
    hikari:
      maximum-pool-size: 10
      connection-timeout: 3000
      pool-name: event-pool

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

  flyway:
    enabled: true

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
    healthcheck:
      enabled: true
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}

logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] [%X{correlationId}] %-5level %logger{36} - %msg%n"
```

**`event_db`, not `auth_db`.** Database per service, physically enforced by using a different database
name and — in production — different credentials that literally cannot read the other.

**`connection-timeout: 3000`** — Phase 2a's Drill 3 showed a 30-second hang when Postgres was down.
Three seconds is far better: fail fast, let the caller decide. This is a small preview of Phase 7's
whole argument.

**`eureka.client.healthcheck.enabled: true`** — promised in Phase 1. Now Eureka reports this instance's
status from `/actuator/health`, which includes the database. A service with a dead database reports
`DOWN` and stops receiving traffic instead of accepting requests it cannot serve.

**Copy `CorrelationIdFilter`** from auth-service into
`com.eventbooking.event.config` (Phase 3, step 9). Same file, different package. Copying a small
technical filter across services is fine; a shared module for it would couple deployments.

## Step 3 — Main class

**File:** `.../event/EventServiceApplication.java`

```java
package com.eventbooking.event;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EventServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventServiceApplication.class, args);
    }
}
```

## Step 4 — The events table

**File:** `.../resources/db/migration/V1__create_events_table.sql`

```sql
CREATE TABLE events (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200)   NOT NULL,
    description     TEXT,
    venue           VARCHAR(200)   NOT NULL,
    starts_at       TIMESTAMPTZ    NOT NULL,
    capacity        INTEGER        NOT NULL,
    available_seats INTEGER        NOT NULL,
    price_cents     BIGINT         NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    created_by      VARCHAR(255)   NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT ck_events_capacity_positive   CHECK (capacity > 0),
    CONSTRAINT ck_events_seats_not_negative  CHECK (available_seats >= 0),
    CONSTRAINT ck_events_seats_within_cap    CHECK (available_seats <= capacity),
    CONSTRAINT ck_events_price_not_negative  CHECK (price_cents >= 0)
);

CREATE INDEX idx_events_status_starts_at ON events (status, starts_at);
CREATE INDEX idx_events_created_by ON events (created_by);
```

**Five decisions worth defending:**

**`price_cents BIGINT`, never a floating-point type for money.** `0.1 + 0.2 != 0.3` in binary floating
point. Store the smallest unit as an integer, format for display at the edge. This is one of the most
common real-world bugs in junior code. (`NUMERIC(19,4)` is the other correct answer; integers are
simpler and faster.)

**`CHECK` constraints.** The same rules live in the entity — so why here too? Because the entity
protects *your application*, and the constraint protects *the data*. A bad migration, a manual
`UPDATE` in psql, or a second application would all sail past your Java rules. `available_seats >= 0`
enforced by Postgres means negative seats are impossible, full stop.

**`version BIGINT`** — the optimistic locking column. Nothing uses it yet; Phase 8 does. Adding it now
means no migration later, and the cost is one column.

**`created_by VARCHAR(255)` holding an email, not a foreign key to a `users` table.** There is no users
table in this database and there must never be one — that data belongs to auth-service. **This is
database-per-service in practice: cross-service references are plain values, never foreign keys.**
Losing referential integrity across services is a real cost of microservices, and it is paid on
purpose.

**`idx_events_status_starts_at`** — a composite index for the main query, "published events, soonest
first". Column order matters: an index on `(status, starts_at)` also serves a query filtering on
`status` alone, but *not* one filtering on `starts_at` alone. Leftmost-prefix rule.

## Step 5 — The status enum with transitions

**File:** `.../domain/EventStatus.java`

```java
package com.eventbooking.event.domain;

import java.util.EnumSet;
import java.util.Set;

public enum EventStatus {

    DRAFT,
    PUBLISHED,
    CANCELLED,
    COMPLETED;

    public boolean canTransitionTo(EventStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<EventStatus> allowedTargets() {
        return switch (this) {
            case DRAFT     -> EnumSet.of(PUBLISHED, CANCELLED);
            case PUBLISHED -> EnumSet.of(CANCELLED, COMPLETED);
            case CANCELLED, COMPLETED -> EnumSet.noneOf(EventStatus.class);
        };
    }

    public boolean acceptsBookings() {
        return this == PUBLISHED;
    }
}
```

**The state machine from concept A3, in code.** `CANCELLED` and `COMPLETED` are terminal — nothing
leads out of them. That single fact prevents a whole family of bugs where a cancelled event quietly
comes back to life.

`acceptsBookings()` gives the rule a name. Callers write `if (status.acceptsBookings())` instead of
`if (status == PUBLISHED)`. When the rule changes — say `LAST_MINUTE` also accepts bookings — you edit
one method rather than hunting for comparisons.

The `switch` is exhaustive over the enum, so **adding a new status makes the code fail to compile**
until you decide its transitions. The compiler becomes a checklist.

## Step 6 — The Event aggregate root

**File:** `.../domain/Event.java`

Fields first:

```java
package com.eventbooking.event.domain;

import com.eventbooking.event.exception.InvalidEventStateException;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "available_seats", nullable = false)
    private int availableSeats;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Event() {
    }
}
```

**`@Enumerated(EnumType.STRING)`, never `ORDINAL`.** Ordinal stores the enum's *position* — `DRAFT` is
0, `PUBLISHED` is 1. Insert a new value in the middle of the enum later and every existing row silently
means something different. That is a data-corruption bug with no error message. Always `STRING`.

**`@Version`** maps to the column from V1. Hibernate now manages it: every update includes
`WHERE version = ?` and increments it. Two concurrent updates → the second fails with
`OptimisticLockException`. **It is already on**, quietly protecting you. Phase 8 makes it explicit and
tests it under real concurrency.

**Now the constructor and the business methods.** Add:

```java
    public Event(String title, String description, String venue, Instant startsAt,
                 int capacity, long priceCents, String createdBy) {
        if (capacity <= 0) {
            throw new InvalidEventStateException("Capacity must be greater than zero");
        }
        if (startsAt.isBefore(Instant.now())) {
            throw new InvalidEventStateException("Event cannot start in the past");
        }

        this.title = title;
        this.description = description;
        this.venue = venue;
        this.startsAt = startsAt;
        this.capacity = capacity;
        this.availableSeats = capacity;
        this.priceCents = priceCents;
        this.createdBy = createdBy;
        this.status = EventStatus.DRAFT;
    }
```

**The constructor is a gate.** An `Event` object that exists is a *valid* `Event` — capacity positive,
start date in the future, status `DRAFT`, all seats free. There is no path to a half-valid one.

`availableSeats = capacity` encodes "a new event starts empty" in the one place it can never be
forgotten.

New events start as `DRAFT`, not `PUBLISHED`: an admin can prepare an event and publish it
deliberately. Defaults are policy decisions.

**Status changes:**

```java
    public void publish() {
        transitionTo(EventStatus.PUBLISHED);
    }

    public void cancel() {
        transitionTo(EventStatus.CANCELLED);
    }

    public void complete() {
        transitionTo(EventStatus.COMPLETED);
    }

    private void transitionTo(EventStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidEventStateException(
                    "Cannot change status from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }
```

One private method holds the rule; three public methods give it business names. Callers write
`event.publish()`, which reads like the domain rather than like a field assignment.

**Seat management — the heart of the whole project:**

```java
    public void reserveSeats(int quantity) {
        if (quantity <= 0) {
            throw new InvalidEventStateException("Quantity must be positive");
        }
        if (!status.acceptsBookings()) {
            throw new InvalidEventStateException("Event is not open for booking: " + status);
        }
        if (quantity > availableSeats) {
            throw new InsufficientSeatsException(id, quantity, availableSeats);
        }

        this.availableSeats -= quantity;
        this.updatedAt = Instant.now();
    }

    public void releaseSeats(int quantity) {
        if (quantity <= 0) {
            throw new InvalidEventStateException("Quantity must be positive");
        }

        this.availableSeats = Math.min(capacity, availableSeats + quantity);
        this.updatedAt = Instant.now();
    }
```

**Read `reserveSeats` slowly — this method is why the architecture looks the way it does.**

Every invariant from concept A2 is checked here, inside the aggregate. There is no way to reserve seats
on a cancelled event, no way to go negative, no way to over-release beyond capacity. **No caller can
get it wrong, because no caller can reach the field.**

In Phase 12 this exact method is called from a **Kafka consumer** rather than a controller. Because the
rules live in the entity and not in an HTTP layer, that will require no changes at all. That is the
payoff of putting logic in the domain — and a concrete answer to "why bother with DDD?"

`releaseSeats` uses `Math.min(capacity, ...)` rather than throwing on overflow. Deliberate: releases
come from cancellations, and in a distributed system a cancellation message can arrive **twice**
(Phase 10). Clamping makes a duplicate release harmless instead of corrupting data. That is
**idempotent thinking**, and Phase 9 is built on it.

**Then the updates and getters:**

```java
    public void updateDetails(String title, String description, String venue,
                              Instant startsAt, long priceCents) {
        if (status == EventStatus.CANCELLED || status == EventStatus.COMPLETED) {
            throw new InvalidEventStateException("Cannot edit a " + status + " event");
        }

        this.title = title;
        this.description = description;
        this.venue = venue;
        this.startsAt = startsAt;
        this.priceCents = priceCents;
        this.updatedAt = Instant.now();
    }

    public int bookedSeats() {
        return capacity - availableSeats;
    }

    public boolean isSoldOut() {
        return availableSeats == 0;
    }
```

(Add plain getters for every field. **No setters** — that is the whole design.)

**Capacity is deliberately not editable.** Lowering it below the seats already sold would create an
event that is oversold, and there is no sensible automatic answer to that. Leaving it out of
`updateDetails` means the question never arises. When the business asks for it, it needs its own method
with its own rules — and that conversation is the right one to have.

`bookedSeats()` and `isSoldOut()` are **derived** values, not stored columns. Storing them would mean
two sources of truth that can drift apart. Compute what you can derive.

## Step 7 — Exceptions

**File:** `.../exception/EventNotFoundException.java`

```java
package com.eventbooking.event.exception;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(Long id) {
        super("Event not found: " + id);
    }
}
```

**File:** `.../exception/InvalidEventStateException.java`

```java
package com.eventbooking.event.exception;

public class InvalidEventStateException extends RuntimeException {
    public InvalidEventStateException(String message) {
        super(message);
    }
}
```

**File:** `.../exception/InsufficientSeatsException.java`

```java
package com.eventbooking.event.exception;

public class InsufficientSeatsException extends RuntimeException {

    private final Long eventId;
    private final int requested;
    private final int available;

    public InsufficientSeatsException(Long eventId, int requested, int available) {
        super("Event %d has %d seats left, %d requested".formatted(eventId, available, requested));
        this.eventId = eventId;
        this.requested = requested;
        this.available = available;
    }

    public Long getEventId() {
        return eventId;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
```

**This one carries structured data, not just a message string.** In Phase 12 a Kafka consumer catches
it and must publish a `SeatsRejected` event containing the numbers. Parsing them back out of a message
string would be miserable. **Exceptions crossing a service boundary should carry fields.**

## Step 8 — Repository

**File:** `.../infrastructure/EventRepository.java`

```java
package com.eventbooking.event.infrastructure;

import com.eventbooking.event.domain.Event;
import com.eventbooking.event.domain.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface EventRepository extends JpaRepository<Event, Long> {

    Page<Event> findByStatus(EventStatus status, Pageable pageable);

    Page<Event> findByStatusAndStartsAtAfter(EventStatus status, Instant after, Pageable pageable);
}
```

`Page<Event>` rather than `List<Event>`: Spring Data runs the query with `LIMIT`/`OFFSET` **and** a
`count(*)`, returning content plus total elements and total pages. Concept A4 — pagination from day
one.

Both methods are backed by the composite index from V1. The second one matches
`(status, starts_at)` exactly — that is not a coincidence; **design the index and the query together.**

**A caution worth knowing now:** `OFFSET` pagination gets slower the deeper you page, because the
database still walks the skipped rows. Page 5000 is genuinely slow. The fix is **keyset pagination**
(`WHERE starts_at > :last ORDER BY starts_at LIMIT 20`). Overkill here, essential at scale.

## Step 9 — DTOs

**File:** `.../api/dto/CreateEventRequest.java`

```java
package com.eventbooking.event.api.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;

public record CreateEventRequest(

        @NotBlank @Size(max = 200)
        String title,

        @Size(max = 5000)
        String description,

        @NotBlank @Size(max = 200)
        String venue,

        @NotNull @Future
        Instant startsAt,

        @Min(1) @Max(100_000)
        int capacity,

        @PositiveOrZero
        long priceCents
) {
}
```

**No `status` and no `createdBy` field.** Both are decided by the server: status starts as `DRAFT`,
`createdBy` comes from the authenticated identity. Accepting them from the client would let anyone
create an event owned by someone else, or spawn one straight into `PUBLISHED`. **A request DTO should
contain only what the client is genuinely allowed to decide** — this is the mass-assignment lesson from
Phase 2a applied to a second service.

`@Future` on `startsAt` duplicates the constructor's check. Not waste — different jobs. Validation
gives a friendly 400 with a field name; the constructor guarantees no `Event` object can ever be
invalid, including when built by a Kafka consumer that never saw an HTTP request.

`@Max(100_000)` on capacity: without an upper bound, someone sends `Integer.MAX_VALUE` and your
arithmetic overflows. Bound every numeric input.

**File:** `.../api/dto/EventResponse.java`

```java
package com.eventbooking.event.api.dto;

import com.eventbooking.event.domain.Event;
import com.eventbooking.event.domain.EventStatus;

import java.time.Instant;

public record EventResponse(
        Long id,
        String title,
        String description,
        String venue,
        Instant startsAt,
        int capacity,
        int availableSeats,
        int bookedSeats,
        boolean soldOut,
        long priceCents,
        EventStatus status,
        Instant createdAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(), event.getTitle(), event.getDescription(), event.getVenue(),
                event.getStartsAt(), event.getCapacity(), event.getAvailableSeats(),
                event.bookedSeats(), event.isSoldOut(), event.getPriceCents(),
                event.getStatus(), event.getCreatedAt());
    }
}
```

**No `version` and no `createdBy` in the response.** `version` is an internal locking detail — exposing
it invites clients to depend on it. `createdBy` is another user's email; leaking it to every browser is
a small privacy hole with no upside.

`bookedSeats` and `soldOut` are computed. The client should not have to do arithmetic to render a
"Sold out" badge, and if it did, two clients would eventually compute it differently.

**File:** `.../api/dto/PageResponse.java`

```java
package com.eventbooking.event.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
```

**Why not just return Spring's `Page`?** Because its JSON shape is an internal framework detail that
has changed between Spring versions, includes a confusing nested `pageable` object, and even logs a
warning about serialising it directly. Your API contract must not move when you upgrade a library. Six
fields you control beats forty you do not.

## Step 10 — The application service

**File:** `.../application/EventService.java`

Class shell and creation:

```java
package com.eventbooking.event.application;

import com.eventbooking.event.api.dto.CreateEventRequest;
import com.eventbooking.event.domain.Event;
import com.eventbooking.event.domain.EventStatus;
import com.eventbooking.event.exception.EventNotFoundException;
import com.eventbooking.event.infrastructure.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Transactional
    public Event create(CreateEventRequest request, String createdBy) {
        Event event = new Event(
                request.title(),
                request.description(),
                request.venue(),
                request.startsAt(),
                request.capacity(),
                request.priceCents(),
                createdBy);

        Event saved = eventRepository.save(event);
        log.info("Created event id={} title='{}' by={}", saved.getId(), saved.getTitle(), createdBy);
        return saved;
    }
}
```

**Notice how thin this is.** All the rules are in the constructor; the service only orchestrates. That
is the correct split — **the application layer coordinates, the domain layer decides.** A fat service
class with an anaemic entity is the most common way people get this backwards.

`createdBy` is a **parameter**, not read from `SecurityContextHolder` here. The application layer stays
independent of how identity arrived. The controller reads the context and passes it in — so this same
method works unchanged from a Kafka consumer or a scheduled job.

**Reads:**

```java
    @Transactional(readOnly = true)
    public Event getById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Event> listPublished(Pageable pageable) {
        return eventRepository.findByStatusAndStartsAtAfter(
                EventStatus.PUBLISHED, Instant.now(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Event> listAll(Pageable pageable) {
        return eventRepository.findAll(pageable);
    }
```

**`readOnly = true` is not decoration.** It tells Hibernate to skip dirty checking (no snapshot of every
loaded entity, less memory, less CPU) and marks the JDBC transaction read-only, which lets Postgres
optimise and prevents an accidental write.

**Two different list methods on purpose.** The public browse endpoint shows only `PUBLISHED` events
that have not started; the admin endpoint shows everything including drafts. Same table, different
contract, different audience. Mixing them into one method with a boolean flag is how endpoints
accidentally leak drafts.

**Writes:**

```java
    @Transactional
    public Event update(Long id, CreateEventRequest request) {
        Event event = getById(id);
        event.updateDetails(request.title(), request.description(), request.venue(),
                request.startsAt(), request.priceCents());
        log.info("Updated event id={}", id);
        return event;
    }

    @Transactional
    public Event publish(Long id) {
        Event event = getById(id);
        event.publish();
        log.info("Published event id={}", id);
        return event;
    }

    @Transactional
    public Event cancel(Long id) {
        Event event = getById(id);
        event.cancel();
        log.warn("Cancelled event id={} with {} seats booked", id, event.bookedSeats());
        return event;
    }
```

**No `eventRepository.save(...)` in any of them.** Inside `@Transactional`, entities loaded through the
repository are **managed**. At commit, Hibernate compares each one to its loaded snapshot and issues an
`UPDATE` for whatever changed. This is **dirty checking**, and it is JPA's core trick. Calling `save()`
would work and do nothing extra.

That is also why `@Transactional` must be on the method: outside a transaction the entity is
*detached*, your change is applied to a Java object in memory, no SQL is issued, and the request
returns 200 having saved nothing. **A silent no-op is the worst failure mode there is**, and this one
catches people regularly.

`log.warn` on cancel, not `log.info` — cancelling an event with bookings has real consequences. Log
levels are a signal to whoever reads the logs at 3am.

## Step 11 — Header-based authentication

**File:** `.../config/HeaderAuthenticationFilter.java`

```java
package com.eventbooking.event.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_ID = "X-User-Id";
    private static final String USER_ROLES = "X-User-Roles";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String userId = request.getHeader(USER_ID);
        String rolesHeader = request.getHeader(USER_ROLES);

        if (userId != null && !userId.isBlank()) {
            List<SimpleGrantedAuthority> authorities = rolesHeader == null || rolesHeader.isBlank()
                    ? List.of()
                    : Arrays.stream(rolesHeader.split(","))
                            .map(String::trim)
                            .filter(r -> !r.isEmpty())
                            .map(SimpleGrantedAuthority::new)
                            .toList();

            var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
```

**Compare this with Phase 2b's `JwtAuthenticationFilter`.** Same shape, same `OncePerRequestFilter`,
same `SecurityContextHolder`, same three-argument constructor that marks the token authenticated — but
**no signature verification, no key, no JWT library.** The gateway did that work once, and this service
inherits the result.

`SecurityContextHolder.clearContext()` in a `finally`: Phase 2a, concept A2.4. Pooled threads must
never carry an identity into the next request.

**The trust assumption is doing enormous work here**, so state it every time you read this class: these
headers are trustworthy **only** because the gateway strips client copies and the service is not
reachable from outside.

**File:** `.../config/SecurityConfig.java`

```java
package com.eventbooking.event.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final HeaderAuthenticationFilter headerAuthenticationFilter;

    public SecurityConfig(HeaderAuthenticationFilter headerAuthenticationFilter) {
        this.headerAuthenticationFilter = headerAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/*").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated()
            )
            .addFilterBefore(headerAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

**The rules here deliberately mirror the gateway's `PublicEndpoints`.** Two layers saying the same
thing — defence in depth. If the gateway is misconfigured tomorrow, this chain still refuses an
unauthenticated write.

**When you change a rule, change it in both places.** Two sources of truth is a real cost of this
design; the alternative (trusting one layer completely) is worse. Knowing the cost and paying it
deliberately is the whole job.

## Step 12 — Controller

**File:** `.../api/EventController.java`

```java
package com.eventbooking.event.api;

import com.eventbooking.event.api.dto.CreateEventRequest;
import com.eventbooking.event.api.dto.EventResponse;
import com.eventbooking.event.api.dto.PageResponse;
import com.eventbooking.event.application.EventService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public PageResponse<EventResponse> list(
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.ASC)
            Pageable pageable) {

        return PageResponse.from(eventService.listPublished(pageable), EventResponse::from);
    }

    @GetMapping("/{id}")
    public EventResponse getOne(@PathVariable Long id) {
        return EventResponse.from(eventService.getById(id));
    }
}
```

**`@PageableDefault` matters more than it looks.** Without it, a client sending `?size=1000000` makes
your service load a million rows into memory. Spring caps page size at 2000 by default
(`spring.data.web.pageable.max-page-size`), but setting a sensible default and knowing the cap exists
is the point. **Every list endpoint needs a bound.**

Spring builds the `Pageable` from `?page=0&size=20&sort=startsAt,desc` automatically.

**Now the admin endpoints:**

```java
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request,
                                                Authentication authentication) {
        var event = eventService.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(EventResponse.from(event));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EventResponse update(@PathVariable Long id,
                                @Valid @RequestBody CreateEventRequest request) {
        return EventResponse.from(eventService.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public EventResponse publish(@PathVariable Long id) {
        return EventResponse.from(eventService.publish(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public EventResponse cancel(@PathVariable Long id) {
        return EventResponse.from(eventService.cancel(id));
    }
```

**`authentication.getName()` returns the `X-User-Id` header value** — set by the gateway, read by
`HeaderAuthenticationFilter`, stored in the `ThreadLocal`, resolved into this parameter. Four
components, one value, and you built every one of them.

**`POST /{id}/publish` rather than `PATCH` with `{"status":"PUBLISHED"}`.** Publishing is an *action*
with rules, not a field assignment. A status field invites clients to attempt any transition and forces
your code to guess intent. An action endpoint says exactly what is happening — and matches
`event.publish()` one-to-one.

**No `DELETE`.** Events with bookings must never vanish; `cancel` is the real-world operation. When you
find yourself wanting a hard delete on a business entity, ask what the business actually does — it is
almost always a state change plus retention.

## Step 13 — Exception handler

**File:** `.../exception/GlobalExceptionHandler.java`

Copy the structure from auth-service (Phase 2a step 14) — `ApiError`, validation handler, catch-all —
then add the event-specific mappings:

```java
    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EventNotFoundException ex,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiError.of(404, "Not Found", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidEventStateException.class)
    public ResponseEntity<ApiError> handleInvalidState(InvalidEventStateException ex,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InsufficientSeatsException.class)
    public ResponseEntity<ApiError> handleInsufficientSeats(InsufficientSeatsException ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }
```

**Why 409 Conflict and not 400 Bad Request** — this distinction is worth getting right:

- **400** means the request itself is malformed. Sending it again will fail identically.
- **409** means the request is well-formed but conflicts with the **current state**. Publish an event
  that is already cancelled: nothing wrong with the request, the state says no. The same request might
  succeed at a different time, or after someone else's change.

A client can act on that difference: retry-with-backoff for 409, fix-your-code for 400.

## Step 14 — Run it

```bash
mvn -q clean install -DskipTests
```

Start Eureka → auth-service → event-service → gateway. Check the Eureka dashboard shows all three.

```bash
# Log in as a normal user
USER_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}' | jq -r .accessToken)

# Try to create an event as USER → 403
curl -i -X POST http://localhost:8080/api/v1/events \
  -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Rock Night","venue":"Arena","startsAt":"2026-12-01T19:00:00Z","capacity":100,"priceCents":250000}'
```

**403 Forbidden** — authenticated but not authorized. `@PreAuthorize` did that, on the header identity
the gateway forwarded.

**Now become an admin.** Promote yourself using the Phase 2b endpoint (it needs an existing admin, so
for the first one do it in SQL):

```bash
docker exec -it ebp-postgres psql -U ebp -d auth_db \
  -c "INSERT INTO user_roles (user_id, role) VALUES (1, 'ADMIN') ON CONFLICT DO NOTHING;"
```

Log in again to get a token carrying `ROLE_ADMIN` — **the old token still says `ROLE_USER`**, which is
Phase 2b's stale-claims lesson appearing in real life.

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}' | jq -r .accessToken)

# Create → 201, status DRAFT
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Rock Night","description":"Live music","venue":"City Arena","startsAt":"2026-12-01T19:00:00Z","capacity":100,"priceCents":250000}' | jq

# Publish it
curl -s -X POST http://localhost:8080/api/v1/events/1/publish \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq

# Browse without any token — public
curl -s http://localhost:8080/api/v1/events | jq
```

The list response shows your `PageResponse` shape: `content`, `page`, `size`, `totalElements`,
`totalPages`, `last`.

---

## Break it on purpose

### Drill 1 — an invalid state transition

```bash
curl -s -X POST http://localhost:8080/api/v1/events/1/cancel -H "Authorization: Bearer $ADMIN_TOKEN"
curl -i -X POST http://localhost:8080/api/v1/events/1/publish -H "Authorization: Bearer $ADMIN_TOKEN"
```

`409 Conflict`: *Cannot change status from CANCELLED to PUBLISHED.*

The rule lives in `EventStatus.canTransitionTo`, was enforced by `Event.transitionTo`, and became an
HTTP status in one handler. **Three layers, one rule, defined once.**

### Drill 2 — a draft is not public

Create an event and do **not** publish it. Then fetch the public list without a token. It is absent —
`listPublished` filters on `PUBLISHED`. Now fetch it directly by id: `GET /api/v1/events/2` returns it.

**That is a real bug, and finding it yourself is the point.** The list is filtered, the detail endpoint
is not. Fix: make `getById` reject non-published events for unauthenticated callers, or move the detail
endpoint behind auth. Decide which, and note that *you* found it — endpoint-by-endpoint authorization
is easy to get inconsistent.

### Drill 3 — spoof the identity header

```bash
curl -i -X POST http://localhost:8082/api/v1/events \
  -H "X-User-Id: attacker@evil.com" -H "X-User-Roles: ROLE_ADMIN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Hacked","venue":"X","startsAt":"2026-12-01T19:00:00Z","capacity":1,"priceCents":0}'
```

**`201 Created`.** The event exists, owned by `attacker@evil.com`.

Now do the same through the gateway on port 8080: **401**, because the gateway strips those headers.

**This is the trust boundary from concept A5, demonstrated as a working exploit against your own
service.** Nothing is broken in your code — the design *assumes* event-service is unreachable from
outside. On your laptop that assumption is false.

Never leave this drill without being able to state the production fix: private subnets or security
groups so only the gateway can reach 8082, or mTLS in a service mesh, or verify the JWT in each service
too.

### Drill 4 — validation and bounds

```bash
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"","venue":"X","startsAt":"2020-01-01T19:00:00Z","capacity":0,"priceCents":-5}' | jq
```

`400` listing every broken field at once: blank title, past date, capacity below 1, negative price.
Four annotations, no `if` statements.

### Drill 5 — the silent no-op

Temporarily remove `@Transactional` from `EventService.publish`. Call publish. You get `200` and the
response shows `PUBLISHED`.

Now check the database:

```bash
docker exec -it ebp-postgres psql -U ebp -d event_db -c "SELECT id, status FROM events;"
```

**Still `DRAFT`.** No transaction, no managed entity, no dirty checking, no `UPDATE`. The response was
built from an in-memory object that was never saved.

**No error. No log. Nothing.** This is the most dangerous bug class in JPA, and now you have seen it
with your own hands. Put the annotation back.

### Drill 6 — kill the database mid-flight

```bash
docker compose stop postgres
curl -i http://localhost:8080/api/v1/events
```

Fails in about 3 seconds thanks to `connection-timeout: 3000`, instead of 30. Then check the Eureka
dashboard: within a few seconds event-service shows **DOWN**, because
`eureka.client.healthcheck.enabled=true` makes it report the actuator health, and the actuator health
includes the datasource.

**A service that cannot serve requests should stop being offered traffic.** That is the whole point of
health-based discovery, and you can now watch it happen.

Restart Postgres and watch it come back `UP`.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Missing `@Transactional` on a write | Silent no-op, response looks correct | Annotate the method |
| `@Enumerated(ORDINAL)` | Data silently means something else after an enum edit | Always `STRING` |
| `double` for money | Rounding errors in totals | `long` cents or `NUMERIC` |
| Setters on the entity | Invariants bypassed | Behaviour methods only |
| Entity returned from the controller | Internal fields leak; lazy-loading errors | Response DTO |
| Client-supplied `status` / `createdBy` | Privilege escalation | Server decides |
| List endpoint with no page bound | One request loads the table | `@PageableDefault` |
| Returning Spring's `Page` directly | Contract changes on library upgrade | Your own `PageResponse` |
| Foreign key to another service's table | Impossible to split; hidden coupling | Store the id as a value |
| Status rules spread across services | Contradictory behaviour | State machine in the enum |
| `readOnly` missing on read methods | Unnecessary dirty checking | `@Transactional(readOnly = true)` |
| Business rules in the controller | Unreachable from a Kafka consumer | Rules in the domain |

---

## Production considerations

- **Soft delete / archive** instead of `DELETE`, for audit and for bookings that reference old events.
- **Keyset pagination** for deep pages (step 8).
- **Full-text search** for title and venue — `pg_trgm` or a search engine. `LIKE '%x%'` cannot use an
  index.
- **Optimistic locking is already on** via `@Version`. Phase 8 proves it under real concurrency.
- **Outbox rows** will be written in the same transaction as event changes (Phase 11), so
  `EventCreated` / `EventUpdated` can be published reliably.
- **Rate limit the public list endpoint** — it is unauthenticated and the most scrapeable thing you
  have.
- **Money and time zones:** store `TIMESTAMPTZ`, format per user at the edge. An event at "19:00" means
  19:00 *at the venue*.
- **Audit trail** for admin actions on events — who published, who cancelled, when.

---

## Phase 4 checklist

- [ ] `GET /api/v1/events` is public and paginated
- [ ] Creating an event as USER returns 403; as ADMIN returns 201
- [ ] A new event starts as `DRAFT` and is invisible in the public list
- [ ] An invalid transition returns 409 with a clear message
- [ ] Validation errors return 400 with per-field messages
- [ ] You saw the silent no-op when `@Transactional` was removed
- [ ] You spoofed `X-User-Roles` directly against :8082 and can state the production fix
- [ ] Event-service shows `DOWN` in Eureka when Postgres is stopped
- [ ] You can explain aggregate, aggregate root, and invariant in your own words
- [ ] You can explain why `reserveSeats` lives on the entity and not in the service

---

## What Phase 5 does next

`GET /api/v1/events` hits Postgres on every call. It is the most-read endpoint in the system and its
data barely changes.

Next: **Redis caching**. Cache-aside, TTL, invalidation on write, what happens when Redis is down, why
a cached value and the database can disagree, and the cache stampede problem.

**Pre-reading for Phase 5:** cache hit vs miss, TTL, and why "cache invalidation" is famous for being
hard.
