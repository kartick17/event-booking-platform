# Phase 6 — Booking Service and OpenFeign: synchronous service-to-service calls

**Time:** 6–7 hours
**Needs:** Phase 5 finished.
**Pre-reading:** synchronous vs asynchronous calls; what a socket timeout actually is.

You will build booking-service and make it call event-service over HTTP. We start with a simple version that waits for each call. It has known failure cases, which the exercises will demonstrate. Phases 11 and 12 replace those parts with a more reliable design.

---

## In plain English

This phase adds booking-service. It asks event-service for event details, asks it to reserve seats, then saves a booking in its own database. OpenFeign helps write those HTTP calls using a Java interface.

Example: a user books 2 seats. Event-service reduces its available seats by 2. Booking-service saves the event name, agreed price, quantity, and booking reference. These are two separate saves. If the second fails, the first is not automatically undone. Understanding that problem is the main lesson of this phase.

Words used in this phase:

- **Synchronous call:** call another service and wait for its answer.
- **Asynchronous message:** send work or a notification for another service to handle later, without waiting for that work to finish.
- **Feign client:** the interface your code calls to send an HTTP request to another service.
- **Proxy:** an object generated for you that performs work behind an interface.
- **DTO:** a small object containing the fields sent or received.
- **Connect / read timeout:** the waiting limit for connecting / reading a response after connecting.
- **Interceptor:** code that adds shared details, such as headers, to outgoing requests.
- **Snapshot:** a saved copy of values as they were when the booking was made.
- **Compensation:** a separate action that tries to undo an earlier action, such as releasing reserved seats.
- **Dual write:** changing two separate systems for one user action. One change can succeed while the other fails.

Start with A1 and A6, then follow Step 1. First confirm that reserving and releasing seats works. After that, build booking-service using the remaining steps.

---

# PART A — Understand the ideas

## A1. Synchronous or asynchronous — how to actually decide

**Synchronous (REST/Feign):** A calls B and *waits*. A cannot finish until B answers.

**Asynchronous (Kafka):** A publishes a message and continues. B handles it whenever it can.

The decision comes down to one question: **does the caller need the answer to complete its own
response?**

| Situation | Choice | Why |
|---|---|---|
| "Does event 42 exist and cost what?" | sync | The reply cannot be written without it |
| "A booking was created" | async | Nobody is waiting; other services react later |
| "Reserve 2 seats, tell me yes or no" | *looks* sync | …and this is the trap. See below |
| "Send a confirmation email" | async | The user must not wait for SMTP |
| "Is this user an admin?" | sync | Needed to decide the response — though a JWT avoids the call entirely |

### The cost of a synchronous call, stated plainly

**Both services must be available.** In a simplified example where each is available 99% of the time and their failures are independent, the combined availability is `0.99 × 0.99 = 98%` after rounding. Four such services give about 96%. Requiring more services to answer adds more ways for a request to fail.

**Waiting times add up.** If booking-service takes 20 ms for its own work and waits another 30 ms for event-service, the request takes about 50 ms. Several calls in a row can make the user wait longer.

**Slowness can spread.** Booking-service workers wait for event-service. If enough workers are waiting, booking-service cannot serve new requests. The gateway then waits too. One slow service can therefore delay the whole request path. This is called a **cascading failure**.

**Messages reduce waiting for the receiving service.** Once Kafka has safely accepted a message, that service can handle it later. Sending to Kafka still has its own waiting limits and failure cases. If Kafka is slow or unavailable, reliable sending needs extra handling, introduced in Phase 11.

> **How to explain it:** "Use a waiting call when the response needs an immediate answer. Use messages when another service can react later. Calls add waiting time and dependencies; messages need handling for delayed work and failures."

## A2. What booking-service actually needs from event-service

Deliberately small:

| Field | Why |
|---|---|
| `id` | to reference |
| `title` | to show in the booking |
| `startsAt` | to reject bookings for past events |
| `priceCents` | to compute the total |
| `status` | must be `PUBLISHED` |
| `availableSeats` | a fast pre-check |

Booking-service does not need the description, venue, capacity, or `createdBy`. Each service keeps the event fields needed for its own job, as explained in Phase 4.

**Booking-service defines its own `EventDto`.** Do not import event-service's `EventResponse` class. Agree on the JSON fields instead. With unknown fields ignored, event-service can add response fields without forcing booking-service to use them.

**Keep this habit:** agree on the data format, but let each service define its own Java class for that data.

## A3. How Feign works internally

You write an interface describing the HTTP calls. At startup, Feign creates an object that implements it for you. That object is a **proxy**. Calling its method sends the request:

```
@FeignClient(name = "event-service")
interface EventClient { ... }
        │
        ▼
FeignClientFactoryBean creates a JDK dynamic proxy for the interface
        │
        ▼
Contract reads the annotations → a method-by-method template
   GET /api/v1/events/{id}, one path variable, produces JSON
        │
        ▼
call eventClient.getEvent(42)
        │
        ├─ RequestInterceptors run   ← we add headers here
        ├─ resolve name "event-service" via Spring Cloud LoadBalancer → Eureka
        ├─ pick an instance (round robin) → http://192.168.1.5:8082
        ├─ Client sends the HTTP request
        ├─ 2xx  → Decoder (Jackson) → EventDto
        └─ non-2xx → ErrorDecoder → an exception you choose
```

**The pieces you can replace:**

| Piece | Default | Why change it |
|---|---|---|
| `Client` | `HttpURLConnection` | No connection pooling — swap in Apache HttpClient or OkHttp |
| `Encoder` / `Decoder` | Jackson | Rarely |
| `ErrorDecoder` | throws `FeignException` | **Always** — map to your own domain exceptions |
| `RequestInterceptor` | none | **Always** — pass on correlation id and identity |
| `Retryer` | `NEVER_RETRY` | Careful — see Phase 7 |

**`@HttpExchange` — the modern alternative** (the Phase 0 decision we promised to show):

```java
@HttpExchange(url = "/api/v1/events", accept = "application/json")
public interface EventApi {

    @GetExchange("/{id}")
    EventDto getEvent(@PathVariable Long id);
}
```

`@HttpExchange` is another way to describe HTTP calls with an interface. The example uses `RestClient` from Spring Framework 6 and does not need Spring Cloud. This guide keeps Feign so you can learn the tool used in this project. The key idea is the same: call an interface method to make an HTTP request.

## A4. Timeouts — the setting that decides whether you survive

Two different timeouts, and confusing them is common:

- **Connect timeout:** how long to wait while opening the network connection. This guide uses a short limit, around **1–2 seconds**, so an unreachable service does not hold the caller for too long.
- **Read timeout:** how long the client waits for response data after connecting. Choose a limit that fits the time allowed for the whole request.

**Set timeouts rather than relying on defaults.** The Feign defaults described here are 10 seconds to connect and 60 seconds to read. A long read wait can keep a Tomcat worker busy for a minute. Enough waiting requests can use all workers even if booking-service itself has not crashed.

**Start with the total time the user request is allowed to take.** If booking has 2 seconds total, its call to event-service must leave time for its own work too. Giving that call roughly 1 second may fit; giving it longer than the whole request budget does not.

**A timeout means we did not receive the answer in time.** It does not prove the other service did nothing. The other service may have saved its change before the response was lost.

```
booking ──POST /reserve──▶ event-service
                              seats reserved, committed
        ◀── (timeout) ──✗     response lost
```

Booking-service sees a timeout, but 2 seats may already be reserved. Retrying could reserve 2 more. Phase 9 makes repeated requests safe; later phases make recovery from partial work more reliable.

## A5. What must travel with every hop

**Forward the correlation id.** This is the request label from Phase 3. Carrying the same id to event-service lets you find both services' logs for one booking.

**Forward the verified user details.** Event-service reads `X-User-Id` and `X-User-Roles`. Without them, a protected operation sees no authenticated user and returns 401.

A `RequestInterceptor` adds these headers to outgoing calls in one place, so each method does not have to repeat the code.

**Later, calls may run without an incoming HTTP request.** `RequestContextHolder` keeps request data on the current thread. An `@Async` task, `CompletableFuture`, or Kafka consumer may have no such data. The interceptor must handle that case, as with the security context in Phase 2a.

## A6. The design we are about to build is wrong — and that is the plan

Booking a seat, done synchronously:

```
1. booking-service: POST /api/v1/events/1/reserve  (event-service reserves 2 seats)
2. booking-service: INSERT INTO bookings ...       (its own database)
```

These changes use **two separate database transactions**. If reserving succeeds but saving the booking fails, the seats remain reserved without a booking. The booking database cannot automatically undo a change in the event database.

`@Transactional` protects changes made through the local database transaction. It does not include an HTTP call to another service. A distributed transaction would need extra support and coordination across systems; this project uses messages and recovery actions instead.

**Build the simple version first.** Drill 3 shows how the booking save and the undo action can fail. After seeing that happen, the outbox in Phase 11 and saga in Phase 12 have a clear problem to solve.

---

# PART B — Build it

## Step 1 — An internal endpoint on event-service

Booking needs to reserve seats. Add to `EventService` in **event-service**:

```java
    @Transactional
    public Event reserveSeats(Long eventId, int quantity) {
        Event event = getById(eventId);
        event.reserveSeats(quantity);
        log.info("Reserved {} seats on event {} - {} remaining",
                quantity, eventId, event.getAvailableSeats());
        return event;
    }

    @Transactional
    public Event releaseSeats(Long eventId, int quantity) {
        Event event = getById(eventId);
        event.releaseSeats(quantity);
        log.info("Released {} seats on event {} - {} remaining",
                quantity, eventId, event.getAvailableSeats());
        return event;
    }
```

All the rules were already written in Phase 4's aggregate — status check, sufficiency check, no negative
seats. **The service adds nothing but a transaction**, which is exactly how it should look.

**Add the endpoints** to `EventController`:

```java
    @PostMapping("/{id}/reserve")
    @PreAuthorize("isAuthenticated()")
    public EventResponse reserve(@PathVariable Long id,
                                 @RequestParam @Min(1) @Max(10) int quantity) {
        return EventResponse.from(eventService.reserveSeats(id, quantity));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("isAuthenticated()")
    public EventResponse release(@PathVariable Long id,
                                 @RequestParam @Min(1) @Max(10) int quantity) {
        return EventResponse.from(eventService.releaseSeats(id, quantity));
    }
```

**These are internal endpoints reachable from the public gateway, and that is a problem worth naming.**
Any authenticated user can `POST /api/v1/events/1/reserve?quantity=10` directly and drain the inventory
without ever creating a booking.

Real fixes: keep them off the gateway's route list entirely (Phase 3, step 3 — explicit routes are a
security boundary), require a service-to-service credential, or put internal calls on a separate port.
**Leave it exposed for now so you can call it by hand while learning — but write the note, because an
interviewer will spot it.**

**Reserving seats does not remove a cache entry in this example.** Cached `EventResponse` seat counts are for display only; reservation checks use the database. If you want the displayed count refreshed after reservation, the guide suggests adding `@CacheEvict` for that event. Decide how old a display count may be.

## Step 2 — The booking module

**File:** `services/booking-service/pom.xml`

Same dependency list as event-service (web, jpa, validation, security, actuator, eureka, flyway,
postgres), **plus**:

```xml
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
```

Add the module to the root POM.

**File:** `services/booking-service/src/main/resources/application.yml`

```yaml
server:
  port: 8083

spring:
  application:
    name: booking-service
  datasource:
    url: jdbc:postgresql://localhost:5432/booking_db
    username: ebp
    password: ebp_password
    hikari:
      maximum-pool-size: 10
      connection-timeout: 3000
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

feign:
  client:
    config:
      default:
        connectTimeout: 2000
        readTimeout: 3000
        loggerLevel: basic
      event-service:
        connectTimeout: 1000
        readTimeout: 2000
        loggerLevel: full

logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] [%X{correlationId}] %-5level %logger{36} - %msg%n"
  level:
    com.eventbooking.booking.infrastructure.EventClient: DEBUG
```

**The Feign block is the important part.** `default` applies to every client; a named block overrides it
per client. Concept A4: 1 s connect, 2 s read — nothing like the 10 s/60 s defaults.

**`loggerLevel: full` shows request and response headers and bodies.** It is useful locally, but may expose tokens such as `Authorization` in logs. Use `basic` for production logging of method, URL, status, and timing. Feign also needs the matching `logging.level` set to DEBUG for its configured logging to appear.

Copy `CorrelationIdFilter`, `HeaderAuthenticationFilter`, `SecurityConfig`, `ApiError` and
`GlobalExceptionHandler` over from event-service, changing packages. Same reasoning as before: small
technical classes are copied, never shared.

## Step 3 — Main class

**File:** `.../booking/BookingServiceApplication.java`

```java
package com.eventbooking.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
```

`@EnableFeignClients` triggers the scan for `@FeignClient` interfaces and creates the proxies described
in concept A3. Without it, injecting `EventClient` fails at startup with "no qualifying bean" — a clear
error, at least.

## Step 4 — The bookings table

**File:** `.../resources/db/migration/V1__create_bookings_table.sql`

```sql
CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    booking_ref     VARCHAR(20)  NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    event_id        BIGINT       NOT NULL,
    event_title     VARCHAR(200) NOT NULL,
    quantity        INTEGER      NOT NULL,
    unit_price_cents BIGINT      NOT NULL,
    total_cents     BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_bookings_ref UNIQUE (booking_ref),
    CONSTRAINT ck_bookings_quantity CHECK (quantity > 0 AND quantity <= 10),
    CONSTRAINT ck_bookings_total CHECK (total_cents >= 0)
);

CREATE INDEX idx_bookings_user_id ON bookings (user_id, created_at DESC);
CREATE INDEX idx_bookings_event_id ON bookings (event_id);
CREATE INDEX idx_bookings_status ON bookings (status);
```

**Two design points that matter more than they look:**

**`event_id BIGINT` has no foreign key.** The event row is in another database. Booking-service must check the event through the service API; its own database cannot guarantee that the referenced event exists.

**`event_title` and `unit_price_cents` are copied, not looked up.** This is a **snapshot**, and it is
deliberate:

1. A booking must still render if event-service is down.
2. If the admin renames the event or changes the price, **the booking must show what the user actually
   agreed to.** A booking that without a clear error changes price is a legal problem, not just a bug.

**Denormalising across a service boundary is correct** — the opposite of what a single-database instinct
says. The rule: copy the values that were part of the agreement; look up the values that must be
current.

`booking_ref` is the human-facing id (`BK-7F3A91C2`). Exposing sequential database ids lets anyone count
your bookings and guess their neighbours' — an enumeration weakness on top of an information leak.

`idx_bookings_user_id (user_id, created_at DESC)` matches "my bookings, newest first" exactly — including
the sort direction, so the database can walk the index instead of sorting.

## Step 5 — Status enum

**File:** `.../domain/BookingStatus.java`

```java
package com.eventbooking.booking.domain;

import java.util.EnumSet;
import java.util.Set;

public enum BookingStatus {

    PENDING,
    CONFIRMED,
    CANCELLED,
    REJECTED;

    public boolean canTransitionTo(BookingStatus target) {
        return switch (this) {
            case PENDING   -> EnumSet.of(CONFIRMED, REJECTED, CANCELLED).contains(target);
            case CONFIRMED -> target == CANCELLED;
            case CANCELLED, REJECTED -> false;
        };
    }

    public boolean isFinal() {
        return this == CANCELLED || this == REJECTED;
    }
}
```

A successful booking in this phase ends as `CONFIRMED`; a failed request throws an exception. `PENDING` and `REJECTED` prepare for Phase 12, where a booking waits for a message before becoming confirmed or rejected.

Putting them in now means no migration later, and it makes the shape of the final design visible while
you build the temporary one.

## Step 6 — The Booking aggregate

**File:** `.../domain/Booking.java`

```java
package com.eventbooking.booking.domain;

import com.eventbooking.booking.exception.InvalidBookingStateException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_ref", nullable = false, unique = true, length = 20)
    private String bookingRef;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "event_title", nullable = false)
    private String eventTitle;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Booking() {
    }

    public Booking(String userId, Long eventId, String eventTitle,
                   int quantity, long unitPriceCents) {
        if (quantity < 1 || quantity > 10) {
            throw new InvalidBookingStateException("Quantity must be between 1 and 10");
        }

        this.bookingRef = "BK-" + UUID.randomUUID().toString()
                .substring(0, 8).toUpperCase();
        this.userId = userId;
        this.eventId = eventId;
        this.eventTitle = eventTitle;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
        this.totalCents = unitPriceCents * quantity;
        this.status = BookingStatus.PENDING;
    }
}
```

**Calculate `totalCents` in the constructor.** Do not accept the price or total chosen by the client. A user can edit a request. Use the price fetched by the server and multiply it by the requested quantity.

Integer cents throughout, so `unitPriceCents * quantity` is exact. Phase 4's money rule again.

**Now the behaviour:**

```java
    public void confirm() {
        transitionTo(BookingStatus.CONFIRMED);
    }

    public void reject() {
        transitionTo(BookingStatus.REJECTED);
    }

    public void cancel(String requestedBy) {
        if (!userId.equals(requestedBy)) {
            throw new InvalidBookingStateException("A booking can only be cancelled by its owner");
        }
        transitionTo(BookingStatus.CANCELLED);
    }

    private void transitionTo(BookingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidBookingStateException(
                    "Cannot change booking from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }
```

(Add getters. No setters.)

**`cancel` checks who owns the booking inside the Booking object.** That keeps the check active when the method is called from somewhere other than an HTTP controller, including a Kafka consumer in Phase 12.

**Different checks answer different questions.** The gateway can check "is this user logged in?" or "is this an admin?" Booking code must check "does this particular booking belong to this user?" because it has the booking data.

## Step 7 — The Feign client

**File:** `.../infrastructure/dto/EventDto.java`

```java
package com.eventbooking.booking.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventDto(
        Long id,
        String title,
        Instant startsAt,
        int availableSeats,
        long priceCents,
        String status
) {
    public boolean isBookable() {
        return "PUBLISHED".equals(status) && startsAt.isAfter(Instant.now());
    }

    public boolean hasSeats(int quantity) {
        return availableSeats >= quantity;
    }
}
```

**`@JsonIgnoreProperties(ignoreUnknown = true)` allows extra response fields.** Event-service may send more fields than booking-service needs. This annotation lets the local DTO read its fields and ignore the others.

**Keep `status` as a `String` at this boundary.** If event-service later returns `POSTPONED`, a local enum without that value could fail while reading the JSON. A string can be read safely; the booking rule still rejects it because it is not `"PUBLISHED"`.

`isBookable()` and `hasSeats()` put the interpretation next to the data. Booking-service decides what
event-service's data *means to it* — which is exactly what a bounded context does.

**File:** `.../infrastructure/EventClient.java`

```java
package com.eventbooking.booking.infrastructure;

import com.eventbooking.booking.config.FeignClientConfig;
import com.eventbooking.booking.infrastructure.dto.EventDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "event-service",
        configuration = FeignClientConfig.class
)
public interface EventClient {

    @GetMapping("/api/v1/events/{id}")
    EventDto getEvent(@PathVariable("id") Long id);

    @PostMapping("/api/v1/events/{id}/reserve")
    EventDto reserveSeats(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);

    @PostMapping("/api/v1/events/{id}/release")
    EventDto releaseSeats(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);
}
```

`name = "event-service"` is the service name, not a fixed URL. Spring Cloud LoadBalancer uses the Eureka list to choose an instance. The Feign-generated implementation sends the request to that address.

**Name Feign path variables:** use `@PathVariable("id")`. Reading a parameter name from compiled Java depends on the `-parameters` setting. Stating the name avoids errors caused by a missing parameter name.

## Step 8 — Feign configuration

**File:** `.../config/FeignClientConfig.java`

Start with header propagation:

```java
package com.eventbooking.booking.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class FeignClientConfig {

    @Bean
    public RequestInterceptor propagationInterceptor() {
        return template -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
                return;
            }
            var request = servletAttributes.getRequest();

            copyHeader(template, request.getHeader("X-Correlation-Id"), "X-Correlation-Id");
            copyHeader(template, request.getHeader("X-User-Id"), "X-User-Id");
            copyHeader(template, request.getHeader("X-User-Roles"), "X-User-Roles");
        };
    }

    private void copyHeader(feign.RequestTemplate template, String value, String name) {
        if (value != null && !value.isBlank()) {
            template.header(name, value);
        }
    }
}
```

**Do not add `@Configuration` to this class here.** If Spring finds it during normal scanning, its settings can apply to other Feign clients too. Referencing it through `@FeignClient(configuration = ...)` keeps these settings attached to the intended client.

**What this fixes:** without it, the call to event-service arrives with no `X-User-Id`, the
`HeaderAuthenticationFilter` builds no `Authentication`, and `@PreAuthorize("isAuthenticated()")`
returns 401. Also the correlation chain would end at the boundary (concept A5).

**The `instanceof` check handles calls with no HTTP request.** A Kafka consumer or `@Async` method may make `getRequestAttributes()` return null. Without the guard, the interceptor can throw a NullPointerException, also called an NPE.

**Now the error decoder.** Add:

```java
    @Bean
    public ErrorDecoder errorDecoder() {
        return (methodKey, response) -> switch (response.status()) {
            case 404 -> new EventNotFoundException(
                    "Event not found (called %s)".formatted(methodKey));
            case 409 -> new SeatsUnavailableException(
                    "Seats unavailable (called %s)".formatted(methodKey));
            case 400 -> new IllegalArgumentException(
                    "Bad request to event-service: " + methodKey);
            default -> {
                if (response.status() >= 500) {
                    yield new EventServiceUnavailableException(
                            "event-service returned " + response.status());
                }
                yield feign.FeignException.errorStatus(methodKey, response);
            }
        };
    }
```

**Why this is not optional.** Without an `ErrorDecoder`, every non-2xx becomes a generic
`FeignException` and your business code ends up doing this:

```java
catch (FeignException e) {
    if (e.status() == 404) { ... }     // HTTP details leaking into domain logic
}
```

Now booking-service's application layer catches `EventNotFoundException` and never learns that HTTP was
involved. **Translate protocol errors into domain errors at the boundary** — the same principle as
`@RestControllerAdvice` doing the reverse on the way out.

**The 4xx/5xx split is the one that matters for Phase 7:**

- **4xx = we are wrong.** Retrying sends the identical wrong request. Never retry.
- **5xx or a timeout = they are struggling.** Retrying *may* help.

`EventServiceUnavailableException` names the retryable case, and Phase 7's circuit breaker triggers on
exactly this type.

## Step 9 — The booking use case

**File:** `.../application/BookingService.java`

```java
package com.eventbooking.booking.application;

import com.eventbooking.booking.api.dto.CreateBookingRequest;
import com.eventbooking.booking.domain.Booking;
import com.eventbooking.booking.exception.*;
import com.eventbooking.booking.infrastructure.BookingRepository;
import com.eventbooking.booking.infrastructure.EventClient;
import com.eventbooking.booking.infrastructure.dto.EventDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final EventClient eventClient;

    public BookingService(BookingRepository bookingRepository, EventClient eventClient) {
        this.bookingRepository = bookingRepository;
        this.eventClient = eventClient;
    }

    public Booking create(CreateBookingRequest request, String userId) {
        EventDto event = eventClient.getEvent(request.eventId());

        if (!event.isBookable()) {
            throw new EventNotBookableException(
                    "Event %d is not open for booking".formatted(event.id()));
        }
        if (!event.hasSeats(request.quantity())) {
            throw new SeatsUnavailableException(
                    "Only %d seats left".formatted(event.availableSeats()));
        }

        eventClient.reserveSeats(event.id(), request.quantity());

        try {
            Booking booking = persistConfirmed(request, userId, event);
            log.info("Booking {} created for user {} on event {}",
                    booking.getBookingRef(), userId, event.id());
            return booking;

        } catch (RuntimeException ex) {
            log.error("Booking persistence failed after reserving {} seats on event {} - "
                            + "attempting compensation",
                    request.quantity(), event.id(), ex);
            compensate(event.id(), request.quantity());
            throw ex;
        }
    }

    @Transactional
    protected Booking persistConfirmed(CreateBookingRequest request, String userId, EventDto event) {
        Booking booking = new Booking(userId, event.id(), event.title(),
                request.quantity(), event.priceCents());
        booking.confirm();
        return bookingRepository.save(booking);
    }

    private void compensate(Long eventId, int quantity) {
        try {
            eventClient.releaseSeats(eventId, quantity);
            log.info("Compensation succeeded: released {} seats on event {}", quantity, eventId);
        } catch (RuntimeException ex) {
            log.error("COMPENSATION FAILED - {} seats leaked on event {}. Manual fix required.",
                    quantity, eventId, ex);
        }
    }
}
```

**This method is the centrepiece of the phase. Read it as an argument, not as code.**

**The first seat check is only an early check.** Another booking can change the count before `reserveSeats` runs. The final decision belongs to event-service when it updates its live row. A successful `hasSeats()` check does not guarantee a later reservation.

**Do not keep the booking database transaction open while waiting for HTTP calls.** That can hold a database connection for the whole wait. With only 10 connections, enough waiting requests can leave none free. The intended transaction is the small database-only part in `persistConfirmed`.

**Important limitation in this example:** `create` calls `persistConfirmed` on the same object. That call skips Spring's transaction wrapper, so its inner `@Transactional` is not applied as intended. This is the **self-invocation problem**. The normal fix is to move the transactional method to a separate Spring bean. The example is left as written so you can recognise it.

**Compensation means trying to undo an earlier action.** Here, if saving the booking fails, we call release to return the seats. If release also fails, we log the problem. This approach has limits:

- If booking-service **crashes** after reservation but before saving, the release code may never run.
- If `releaseSeats` fails, the reserved count stays wrong until recovery succeeds or someone repairs it.
- If the reservation call times out, we do not know whether it reserved seats. Releasing seats without knowing that can also make the count wrong.

**Phases 11 and 12 address these gaps.** They save the work needed for recovery so it can survive a restart and be retried. A `catch` block alone can only help while the current process is still running.

**Add the remaining methods:**

```java
    @Transactional(readOnly = true)
    public Booking getForUser(String bookingRef, String userId) {
        Booking booking = bookingRepository.findByBookingRef(bookingRef)
                .orElseThrow(() -> new BookingNotFoundException(bookingRef));

        if (!booking.getUserId().equals(userId)) {
            throw new BookingNotFoundException(bookingRef);
        }
        return booking;
    }

    @Transactional
    public Booking cancel(String bookingRef, String userId) {
        Booking booking = getForUser(bookingRef, userId);
        int quantity = booking.getQuantity();

        booking.cancel(userId);

        try {
            eventClient.releaseSeats(booking.getEventId(), quantity);
        } catch (RuntimeException ex) {
            log.error("Cancelled booking {} but failed to release {} seats on event {}",
                    bookingRef, quantity, booking.getEventId(), ex);
        }
        return booking;
    }
```

**Return "not found" when the booking belongs to another user.** Returning "forbidden" would reveal that the reference exists. The user should only learn about their own bookings.

**Cancellation can also partly fail.** The local booking becomes cancelled, but the call to release seats may fail. This version logs the release failure and lets cancellation continue. The records then disagree: a cancelled booking can still hold seats. Phase 12 uses messages that can be retried to complete the release.

## Step 10 — DTOs, repository, controller

**File:** `.../api/dto/CreateBookingRequest.java`

```java
package com.eventbooking.booking.api.dto;

import jakarta.validation.constraints.*;

public record CreateBookingRequest(
        @NotNull Long eventId,
        @Min(1) @Max(10) int quantity
) {
}
```

**Two fields only.** No price, no total, no user id — every one of those is decided server-side. Phase
4's rule about request DTOs, and the money rule from step 6.

**File:** `.../infrastructure/BookingRepository.java`

```java
package com.eventbooking.booking.infrastructure;

import com.eventbooking.booking.domain.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingRef(String bookingRef);

    Page<Booking> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
```

`findByUserIdOrderByCreatedAtDesc` matches the `(user_id, created_at DESC)` index exactly — query and
index designed together, as in Phase 4.

**File:** `.../api/BookingController.java`

```java
package com.eventbooking.booking.api;

import com.eventbooking.booking.api.dto.*;
import com.eventbooking.booking.application.BookingService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/bookings")
@PreAuthorize("isAuthenticated()")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                  Authentication authentication) {
        var booking = bookingService.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(BookingResponse.from(booking));
    }

    @GetMapping("/{bookingRef}")
    public BookingResponse getOne(@PathVariable String bookingRef, Authentication authentication) {
        return BookingResponse.from(
                bookingService.getForUser(bookingRef, authentication.getName()));
    }

    @GetMapping
    public PageResponse<BookingResponse> myBookings(
            @PageableDefault(size = 20) Pageable pageable, Authentication authentication) {
        return PageResponse.from(
                bookingService.listForUser(authentication.getName(), pageable),
                BookingResponse::from);
    }

    @PostMapping("/{bookingRef}/cancel")
    public BookingResponse cancel(@PathVariable String bookingRef, Authentication authentication) {
        return BookingResponse.from(
                bookingService.cancel(bookingRef, authentication.getName()));
    }
}
```

**Put `@PreAuthorize` on the class** because every booking endpoint needs a logged-in user. A later endpoint added to the class then inherits the same requirement.

**The user id always comes from `Authentication`, never from the request.** `POST /bookings` with
`{"userId":"someone@else.com"}` is impossible because the DTO has no such field, and the controller
would ignore it anyway. Two independent defences.

Write `BookingResponse` and copy `PageResponse` from event-service.

## Step 11 — Run it

Start everything: Eureka → auth → event → booking → gateway.

```bash
USER_TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}' | jq -r .accessToken)

# check seats before
curl -s http://localhost:8080/api/v1/events/1 | jq '{availableSeats}'

# book two seats
curl -s -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
  -d '{"eventId":1,"quantity":2}' | jq

# check seats after
curl -s http://localhost:8080/api/v1/events/1 | jq '{availableSeats}'
```

Seats dropped by 2. **Two services, two databases, one user action.**

**Follow one booking through the logs.** Local `loggerLevel: full` output shows the call and forwarded `X-Correlation-Id` and `X-User-Id`. Search event-service's logs for that same correlation id to connect the work across services.

### The full request flow

```
1.  client → gateway: POST /api/v1/bookings, Bearer token
2.  gateway: correlation id, strip X-User-*, verify JWT, add X-User-Id/X-User-Roles
3.  gateway: lb://booking-service → Eureka cache → pick an instance
4.  booking-service: CorrelationIdFilter → MDC
5.  booking-service: HeaderAuthenticationFilter → SecurityContext
6.  BookingController → @PreAuthorize passes → BookingService.create
7.  Feign: EventClient.getEvent(1)
      ├─ RequestInterceptor adds X-Correlation-Id, X-User-Id, X-User-Roles
      ├─ LoadBalancer resolves event-service via Eureka
      └─ HTTP GET → event-service
8.  event-service: filters → @Cacheable → Redis HIT → EventDto returned
9.  booking-service: isBookable? hasSeats? → yes
10. Feign: EventClient.reserveSeats(1, 2)
11. event-service: @Transactional → Event.reserveSeats(2) → invariants → UPDATE (version++)
12. booking-service: INSERT INTO bookings ... COMMIT      ← different database, no atomicity
13. 201 Created → gateway → client
```

**Steps 11 and 12 are two separate commits in two separate databases.** Nothing links them. That is the
whole problem, and it is now visible in a numbered list rather than in the abstract.

---

## Break it on purpose

### Drill 1 — event-service down

```bash
# stop event-service, then:
time curl -i -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
  -d '{"eventId":1,"quantity":1}'
```

Fails in about **1 second** — the connect timeout. Now set `connectTimeout: 10000`, restart, and try
again: 10 seconds of a held Tomcat thread.

**That difference is the entire argument of Phase 7.** Multiply by 200 happening at the same time requests and the first
number is a blip while the second is an outage.

Note the response is a 500. It should be a **503 Service Unavailable** with `Retry-After` — a downstream
outage is not an internal server error. Add that mapping to your `GlobalExceptionHandler`.

### Drill 2 — event-service slow

Add a `Thread.sleep(5000)` to event-service's `getById` and restart.

The call reaches the 2-second read timeout. A slow service holds a worker until that limit; a stopped service may refuse a connection immediately. Phase 7 adds limits on how many workers one dependency can keep waiting.

Remove the sleep.

### Drill 3 — the leak (the important one)

Make the booking insert fail *after* the reserve succeeds. Easiest way: temporarily throw at the end of
`persistConfirmed`.

```bash
curl -s http://localhost:8080/api/v1/events/1 | jq '{availableSeats}'   # e.g. 98
curl -i -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
  -d '{"eventId":1,"quantity":2}'                                        # 500
curl -s http://localhost:8080/api/v1/events/1 | jq '{availableSeats}'
```

The compensation runs and the seats come back — **and the log shows it happening.** Good.

**Now break the compensation too.** Stop event-service *during* the failed booking (or throw inside
`compensate`). Seats reserved, no booking, no release:

```
ERROR COMPENSATION FAILED - 2 seats leaked on event 1. Manual fix required.
```

**Two seats can never be sold again. No user, no admin, no process will ever fix it.**

The code has error handling, but still leaves reserved seats with no booking. The reason is that recovery code cannot run after the process has stopped. A crash, lack of memory, or a restart between the two saves can leave partial work behind.

**This is the exact problem Phases 11 and 12 solve.** Not with better `try/catch`, but by making the
undo a durable, retried message instead of an in-memory instruction that dies with the process.

### Drill 4 — no header propagation

Comment out the `RequestInterceptor` and restart. Booking now fails with a **401 from event-service** —
`@PreAuthorize("isAuthenticated()")` sees no `X-User-Id`.

Also grep the correlation id: the trail **stops** at booking-service. Concept A5, demonstrated twice in
one drill.

### Drill 5 — the last seat, twice

Set an event's capacity so exactly 1 seat remains, then:

```bash
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/v1/bookings \
    -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
    -d '{"eventId":1,"quantity":1}' &
done
wait; echo
docker exec -it ebp-postgres psql -U ebp -d event_db \
  -c "SELECT available_seats FROM events WHERE id = 1;"
```

You will likely see **one 201 and four 409s**, with `available_seats = 0`. Several requests may have passed the first check. The protection comes from event-service's row update and `@Version`, which rejects a change based on an older row version.

**Run it several times.** Under the right timing you may see an `OptimisticLockException` surface as a
500 rather than a clean 409. **Phase 8 is about making that behaviour deliberate and correct instead of
accidental.**

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Sharing a DTO class between services | Deploy coupling; a distributed monolith | Copy the shape you need |
| Default Feign timeouts (10 s / 60 s) | Thread pool running out under load | 1–2 s connect, ≤3 s read |
| No `ErrorDecoder` | HTTP status codes leak into domain logic | Map to domain exceptions |
| No `RequestInterceptor` | 401s between services; broken tracing | Pass on the headers |
| `@Transactional` around a remote call | Connection pool used up | Narrow the transaction |
| Feign config class marked `@Configuration` | Applied to every client | Leave it unannotated |
| Retrying 4xx | Same failure, more load | Retry 5xx and timeouts only |
| Trusting a client-supplied total | Users book for 1 cent | Recompute server-side |
| Enum for another service's status field | Breaks when they add a value | `String` at the boundary |
| No `@JsonIgnoreProperties(ignoreUnknown)` | Breaks when they add a field | Add it |
| Assuming a timeout means "did not happen" | Duplicates or leaks | Idempotency (Phase 9) |
| Compensation only in a `catch` block | Leaks whenever the process dies | Durable saga (Phase 12) |

---

## Notes for a real running system

- **Use an HTTP client with connection pooling**, such as Apache HttpClient 5 or OkHttp, when appropriate. Pooling reuses connections instead of opening a new one for every call.
- **Add circuit breakers in Phase 7** so repeated dependency failures do not keep every caller waiting.
- **Use `loggerLevel: basic` in production.** `full` can expose tokens and request data.
- **Return 503 with `Retry-After`** when the dependency is unavailable, rather than an unexplained 500.
- **Keep database transactions separate from remote calls** so waiting does not hold database connections unnecessarily.
- **Protect internal endpoints with service credentials or routing controls**, as noted in Step 1. Ordinary users must not be able to reserve seats without creating a booking.
- **Fit inner timeouts inside the outer request budget:** for example, gateway 5 seconds, booking 3 seconds, event 1 second.
- **Avoid too many calls per booking.** Combine needed reads or keep suitable copies of data where that reduces waiting safely.
- **Add contract tests in Phase 13** to catch response changes that would break booking-service.

---

## Phase 6 checklist

- [ ] A booking through the gateway reduces `availableSeats`
- [ ] One correlation id appears in gateway, booking and event logs
- [ ] Feign resolves `event-service` through Eureka, with no URL anywhere
- [ ] Event-service down → booking fails in ~1 s, not 10
- [ ] Event-service slow → read timeout fires, and you know why slow is worse than down
- [ ] You made a booking fail after reserving, and watched compensation run
- [ ] You made **compensation fail**, and saw seats leak permanently
- [ ] Removing the interceptor produced a 401 and broke the trace
- [ ] You can explain why `create` is not `@Transactional`
- [ ] You can explain why booking copies `event_title` and `unit_price_cents`
- [ ] You can state the availability arithmetic: `0.99 × 0.99 = 98%`

---

## What Phase 7 does next

You now have a booking service whose availability is multiplied by event-service's, that holds threads
while waiting, and that falls over when its dependency gets slow.

Next is **Resilience4j**. You will limit waits, decide when to retry, pause calls to failing services, and limit the workers one dependency can use. These tools reduce the effect of a failure, but careless retries can add more load.

**Pre-reading for Phase 7:** cascading failure, and the thread-pool-exhaustion story behind circuit
breakers.
