# Phase 4 — Event Service: bounded context, aggregates, API contracts

**Time:** 6–7 hours
**Needs:** Phase 3 finished (gateway routing and stripping headers).
**Pre-reading:** bounded context; why entities must not leak out of a controller.

This is the first service for the booking business itself. You will build event features and put their rules in one place. That prepares you for Phase 8, where several users try to change the same seats at once.

---

## In plain English

This phase builds the event catalogue: creating an event, publishing it, showing it to users, and cancelling it. It also decides where the rules about seats and event status belong.

For example, an event starts as a draft. An admin publishes it. Users can then browse it. Once cancelled, it must not accept new reservations. We put those rules inside the Event object so every caller uses the same checks.

Words used in this phase:

- **Domain / business rules:** the subject of the application and its rules, such as "seats cannot be negative."
- **Bounded context:** one area with its own meaning of the data. Event-service owns event details; booking-service owns bookings.
- **Entity:** an object with a lasting identity, such as event 42 even after its title changes.
- **Aggregate root:** the main object through which related data is changed and checked. Here, it is Event.
- **Invariant:** a rule that must always hold.
- **State transition:** a change from one status to another, such as DRAFT to PUBLISHED.
- **DTO:** a data transfer object; the fields sent in a request or response.
- **Repository:** code used to load and save database records.
- **Transaction:** a group of database changes that succeed or roll back together.
- **Pagination:** returning a small page of results instead of every record at once.

Start with the event statuses and seat rules. They explain why the classes and database checks in the build steps are needed.

---

# PART A — Understand the ideas

## A1. Bounded context — the idea the whole architecture rests on

A **bounded context** is an area of the application with its own data and rules. Within that area, a word such as "event" has a clear meaning.

Take the word "event":

- To **event-service**, an Event is a concert with a venue, a date, and 500 seats.
- To **booking-service**, an "event" is barely more than an id and a price.
- To a marketing service, it would be a campaign target with an audience segment.

Each service needs a different view of an event. One large shared `Event` class would collect fields for all of them. Then changing it could force several services to change and be released together. This is called a **distributed monolith**.

**Give each service its own model.** Booking-service keeps `eventId` and copies the details it needs. Services agree on the fields they exchange, called a **contract**, rather than importing each other's internal Java classes.

> **How to explain it:** "Each service owns its view of the data. Event-service needs the venue and capacity; booking-service needs the event id and agreed price. They exchange data using an agreed format."

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

**Our ownership rules:** auth-service owns users and login, event-service owns the catalogue and seat counts, and booking-service owns bookings. Only event-service changes seat counts. That gives the database one clear place to protect those changes.

## A2. Aggregate and aggregate root

An **aggregate** is related data changed as one unit. The **aggregate root** is the main object that other code uses to make those changes. It checks the rules before allowing a change.

Here, `Event` is the aggregate root. Its **invariants**, meaning rules that must always hold, are:

1. `availableSeats` is never negative.
2. `availableSeats` never exceeds `capacity`.
3. A `CANCELLED` event never accepts a reservation.
4. Status changes follow the allowed transitions.

Put these checks inside the Event object. Otherwise, every caller must remember to check them separately, and a forgotten check could save bad data. A method such as `reserveSeats` checks the rules before changing the count.

```java
// Rules live outside the object — every caller must remember them
if (event.getAvailableSeats() >= qty && event.getStatus() == PUBLISHED) {
    event.setAvailableSeats(event.getAvailableSeats() - qty);   // and one caller will forget
}

// Rules live inside the object — impossible to get wrong
event.reserveSeats(qty);   // throws if it would break an invariant
```

In the second version, callers ask the object to reserve seats instead of assigning a number directly. **Avoiding public setters** helps keep callers from skipping those checks.

> **How to explain it:** "The aggregate groups data that must stay correct together. Its main object checks the rules, and changes are saved in one transaction. Other parts of the application refer to it by id."

### One aggregate, one transaction

**A useful starting rule:** change one aggregate in a transaction. If a workflow must change data in different services, those changes cannot use one ordinary database transaction. Phase 12 uses a saga to coordinate separate changes.

`Event` and `Booking` are stored in separate services and databases. Saving one does not automatically save or undo the other. Later phases teach how to handle that gap.

## A3. State machines beat boolean flags

Our event status: `DRAFT → PUBLISHED → CANCELLED / COMPLETED`.

Separate boolean fields can allow confusing combinations, such as `isPublished` and `isCancelled` both being true. One status field makes the current state clearer.

Use **one enum for the status** and list the allowed changes. Then reject any status change that is not listed:

```
DRAFT ──publish──▶ PUBLISHED ──complete──▶ COMPLETED
  │                     │
  └──cancel──▶ CANCELLED ◀──cancel──┘
```

The diagram has no arrow from `COMPLETED` to `PUBLISHED`. The code should reject that change, so every caller follows the same rule.

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

**Keep existing clients working.** Prefer changes that add optional data. If an incompatible change is necessary, introduce `/api/v2/` alongside the old API and give clients time to move.

**Add pagination from the start.** Returning all events may work for 50 records but consume too much memory for 500,000. Changing an existing list response to pages later can break clients.

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

**Important limit:** trusting user headers is safe only when outside callers cannot reach event-service directly. Otherwise anyone can send `X-User-Roles: ROLE_ADMIN`. Production needs network protection such as private subnets, security groups, or authenticated service connections using mTLS.

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

**Store money in integer units, such as `price_cents BIGINT`.** Binary floating-point numbers can introduce rounding differences, as in `0.1 + 0.2 != 0.3`. Format the integer for display in the response or client. A fixed decimal type such as `NUMERIC(19,4)` is another option.

**Database `CHECK` constraints protect saved data.** Java checks protect calls through your application. A manual SQL update or another application can skip those Java checks. A database rule such as `available_seats >= 0` also rejects those invalid writes.

**`version BIGINT`** — the optimistic locking column. Nothing uses it yet; Phase 8 does. Adding it now
means no migration later, and the cost is one column.

**`created_by VARCHAR(255)` stores an email.** The users table belongs to auth-service, not this database. We therefore cannot use a normal foreign key to that table. The application must check cross-service references when needed.

**`idx_events_status_starts_at` is an index on two columns.** It helps find events with a chosen status and return them by start time. The column order matters: `(status, starts_at)` is especially useful when the query filters by `status`, the first column. It is not generally the right index for a query only filtering by `starts_at`.

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

**These are the status rules from A3.** `CANCELLED` and `COMPLETED` are final states: there are no allowed changes out of them. This prevents a cancelled event from becoming active again accidentally.

`acceptsBookings()` names the rule clearly. Callers use `if (status.acceptsBookings())` rather than repeating `if (status == PUBLISHED)`. If a later `LAST_MINUTE` status also accepts bookings, change this rule in one place.

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

**Use `@Enumerated(EnumType.STRING)` to store enum names.** `ORDINAL` stores positions such as 0 and 1. Adding a value in the middle later can change what existing positions mean. Storing names avoids that problem.

**`@Version` detects competing updates.** Hibernate checks `WHERE version = ?` when updating and increases the version. If another transaction already changed the row, the update fails with `OptimisticLockException`. Phase 8 explores and handles this conflict.

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

These methods check the rules from A2 before changing seats. They reject reservations on cancelled events and prevent counts below zero or above capacity. Callers use these methods instead of changing the field directly.

In Phase 12, a **Kafka consumer** calls this method after receiving a message. The seat rules stay the same because they belong to the Event object, rather than only to the HTTP controller.

`releaseSeats` uses `Math.min(capacity, ...)` to stop the total exceeding capacity. This is a defensive limit, but **it does not make duplicate releases fully safe**: a repeated release could still increase the count incorrectly below that limit. Phase 9 adds duplicate protection so the same cancellation is not applied twice.

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

**Capacity cannot be edited in this version.** Reducing it below seats already sold would need a separate business decision. If that feature is added later, give it its own method and checks rather than adding it to `updateDetails`.

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

**This exception carries numbers as fields, as well as a message.** Phase 12 needs those numbers to publish a `SeatsRejected` message. Keeping fields avoids trying to recover numbers from a sentence in an error message.

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

**`OFFSET` skips earlier rows before returning a page.** Large page numbers mean more rows to skip. **Keyset pagination** starts after the last value already seen, for example `WHERE starts_at > :last ORDER BY starts_at LIMIT 20`. We do not need that extra design here, but it helps with deep lists.

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

**Do not accept `status` or `createdBy` from the client.** A new event starts as `DRAFT`, and its creator comes from the authenticated user. Letting callers choose those fields could create published events or events owned by someone else. Request DTOs should contain only fields the user may decide.

`@Future` checks `startsAt` at the API boundary and can return a helpful 400 response. The constructor also checks the rule so non-HTTP callers, such as a Kafka consumer, cannot skip it.

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

**Leave `version` and `createdBy` out of the response.** The version is an internal locking detail. The creator value is another user's email, which ordinary event browsing does not need.

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

**Use a response shape you control.** Spring's `Page` contains framework-specific details and its JSON can change after an upgrade. A small response DTO gives clients a stable set of fields.

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

**The service coordinates the work; the Event object checks the business rules.** Here, the constructor handles the event rules, so the service does not need to repeat them.

Pass `createdBy` into the service method. Let the controller read the authenticated user. The service then does not depend on how the request arrived and can also be called from a message handler or scheduled job.

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

**These methods do not need `eventRepository.save(...)`.** The entity was loaded inside `@Transactional`, so Hibernate tracks it. At commit, it writes the changed fields. This automatic tracking is called **dirty checking**.

**The transaction matters.** If an object is no longer tracked by Hibernate, changing it only changes Java memory. Without a transaction or a later save, the database is not updated even if the request appears successful.

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

**Remember the trust rule:** these headers are reliable only when the gateway replaces client-supplied copies and outside callers cannot bypass the gateway.

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
(`spring.data.web.pageable.max-page-size`), but setting a useful default and knowing the cap exists
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

**Publishing is an action with rules.** `POST /{id}/publish` makes that action clear and matches `event.publish()`. A general status update such as `PATCH` with `{"status":"PUBLISHED"}` would also need to reject every forbidden status change.

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

- **400:** something is wrong with the request data or format. Repeating it unchanged will not fix it.
- **409:** the requested action conflicts with the current saved state. For example, a cancelled event cannot be published. The request format is valid, but the event's state does not allow the action.

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
| `@Enumerated(ORDINAL)` | Data without a clear error means something else after an enum edit | Always `STRING` |
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

## Notes for a real running system

- **Archive or mark events as deleted instead of using `DELETE`** when history and existing bookings still refer to them.
- **Use keyset pagination for deep lists**, as explained in Step 8.
- **Plan search indexes for titles and venues.** A normal index is usually unsuitable for `LIKE '%x%'`; `pg_trgm` or a search engine can help this type of search.
- **Keep `@Version` protection.** Phase 8 tests how overlapping updates behave.
- **Save outbox rows with event changes in Phase 11** so messages such as `EventCreated` and `EventUpdated` are not lost between saving and sending.
- **Limit requests to public list endpoints** so automated traffic cannot overwhelm them.
- **Handle money and time consistently.** Store instants using `TIMESTAMPTZ` and display them in the appropriate time zone. A venue time of "19:00" needs the venue's zone to identify the instant.
- **Record admin actions**, including who published or cancelled an event and when.

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
