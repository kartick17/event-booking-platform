# Phase 6 — Booking Service and OpenFeign: synchronous service-to-service calls

**Time:** 6–7 hours
**Needs:** Phase 5 finished.
**Pre-reading:** synchronous vs asynchronous calls; what a socket timeout actually is.

This phase builds the booking service and makes it talk to event-service over HTTP. We will build the
**naive synchronous version on purpose**, then spend the drills proving why it cannot be the final
answer. Phases 11 and 12 replace it. Feeling the problem first is the point.

---

# PART A — Concepts

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

**Availability multiplies.** If booking-service is up 99% of the time and event-service is up 99% of the
time, a booking endpoint that *requires* event-service is up `0.99 × 0.99 = 98%`. Chain four services
and you are at 96%. **Every synchronous dependency makes you less available than your weakest link.**

**Latency adds.** Your 20 ms becomes 20 + 30 = 50 ms. A chain of five calls is a slow endpoint no
single team owns.

**Failure propagates.** Event-service slows down → booking-service threads pile up waiting → booking
runs out of threads → the gateway piles up waiting for booking → the whole platform is down because one
service got slow. **This is a cascading failure, and slowness spreads it faster than crashes do** — a
crash at least frees the thread.

**Async has none of these properties.** Kafka being slow does not block the caller; the message simply
sits in the log.

> **Interview line:** "Use sync when you need the answer to build your response. Use async for
> notifying that something happened. Every sync hop multiplies your failure probability and adds
> latency — that arithmetic is the main argument for event-driven design."

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

**Not** description, venue, capacity, `createdBy`. Concept A1 in Phase 4 — booking-service has its own
bounded context, and "event" means something smaller here.

**So booking-service defines its own `EventDto` with only these fields.** It does **not** import
event-service's `EventResponse`, even though the JSON is identical today. Sharing that class would mean
event-service cannot add a field without a coordinated rebuild — a distributed monolith. Jackson
ignores unknown fields, so a smaller local DTO is naturally forward-compatible.

**This is the single most important habit in this phase.** Copy the shape you need; never share the
class.

## A3. How Feign works internally

You write an interface. You never write an implementation. At startup:

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
| `RequestInterceptor` | none | **Always** — propagate correlation id and identity |
| `Retryer` | `NEVER_RETRY` | Careful — see Phase 7 |

**`@HttpExchange` — the modern alternative** (the Phase 0 decision we promised to show):

```java
@HttpExchange(url = "/api/v1/events", accept = "application/json")
public interface EventApi {

    @GetExchange("/{id}")
    EventDto getEvent(@PathVariable Long id);
}
```

Backed by `RestClient`, built into Spring Framework 6, no Spring Cloud dependency. Same idea, newer
plumbing. **We stay on Feign because you will meet it in real codebases**, but you should be able to
say in an interview that Spring Cloud OpenFeign is feature-frozen and `@HttpExchange` is where new
projects should start.

## A4. Timeouts — the setting that decides whether you survive

Two different timeouts, and confusing them is common:

- **Connect timeout** — how long to wait for the TCP connection. Fails fast when the host is down or
  unreachable. **1–2 seconds is plenty**; a healthy connection on a LAN takes milliseconds.
- **Read timeout** — how long to wait for the response *after* connecting. This must be based on how
  long the call legitimately takes.

**Feign's defaults are 10 s connect and 60 s read.** Those numbers are catastrophic in a request path.
A 60-second read timeout means one slow downstream service can hold a Tomcat thread for a minute. Two
hundred such requests and your thread pool is gone — while the service itself is perfectly healthy.

**Choose read timeouts from the caller's budget, not the callee's speed.** If the gateway gives booking
2 seconds total and booking does one downstream call plus its own work, that call gets ~1 second. A
downstream call allowed to take longer than your own SLA is a bug regardless of how fast it usually is.

**And know what a timeout does *not* tell you.** When a write times out, you do **not** know whether it
happened. The request may have completed on the other side and only the response was lost.

```
booking ──POST /reserve──▶ event-service
                              seats reserved, committed
        ◀── (timeout) ──✗     response lost
```

Booking sees a failure. Two seats are gone. **This ambiguity is why retries need idempotency (Phase 9)
and why the final design uses events instead of a synchronous reserve call.**

## A5. What must travel with every hop

**The correlation id.** Phase 3 built it. Without propagation, the trail stops at the service boundary
and distributed debugging is over.

**The user identity.** Event-service authorises from `X-User-Id` / `X-User-Roles`. A Feign call that
does not forward them arrives unauthenticated and gets a 401 — one of the most common "it works in
Postman but not from the other service" bugs.

Both are added once, in a `RequestInterceptor`, so no individual call can forget.

**A caution for later:** the interceptor reads a `ThreadLocal` (`RequestContextHolder`). In an `@Async`
method, a `CompletableFuture`, or a Kafka consumer there is no incoming request, so both values are
empty. Same thread-local lesson as `SecurityContextHolder` in Phase 2a.

## A6. The design we are about to build is wrong — and that is the plan

Booking a seat, done synchronously:

```
1. booking-service: POST /api/v1/events/1/reserve  (event-service reserves 2 seats)
2. booking-service: INSERT INTO bookings ...       (its own database)
```

Two databases, two transactions, no atomicity. If step 2 fails after step 1 succeeded, **two seats are
reserved for a booking that does not exist.** They can never be sold. Nobody is told.

There is no annotation that fixes this. `@Transactional` covers one database connection. There is no
distributed transaction here — and the alternative (two-phase commit) is slow, needs a coordinator, and
is not what modern systems use.

**Build it anyway.** You will break it in Drill 3, watch the seats leak, and then Phase 11 (outbox) and
Phase 12 (saga) will be answers to a problem you have personally caused, rather than patterns you read
about.

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

Note also that reserve **evicts no cache**: seat counts flow through `EventResponse`, and Phase 5 said
we do not cache inventory. Add `@CacheEvict` on the event key if you want display counts fresh — a good
exercise in deciding what staleness is acceptable.

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

`loggerLevel: full` logs headers and bodies. Superb for learning, **never in production**: it will print
the `Authorization` header and every payload into your logs. `basic` (method, URL, status, timing) is
the production choice. The `logging.level` line is required too — Feign logs at DEBUG, so without it
`loggerLevel` does nothing. A confusing pair on first meeting.

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

**`event_id BIGINT` with no foreign key.** There *cannot* be one — the `events` table is in another
database. Phase 4 said this; here you feel it. Nothing stops a booking referencing event 999 that never
existed. **Referential integrity across services is your code's job, and the check is never atomic.**

**`event_title` and `unit_price_cents` are copied, not looked up.** This is a **snapshot**, and it is
deliberate:

1. A booking must still render if event-service is down.
2. If the admin renames the event or changes the price, **the booking must show what the user actually
   agreed to.** A booking that silently changes price is a legal problem, not just a bug.

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

`PENDING` and `REJECTED` are unused in this phase — a synchronous booking is `CONFIRMED` or it threw an
exception. **They exist because Phase 12's saga needs them:** the booking is created `PENDING`, and a
Kafka reply moves it to `CONFIRMED` or `REJECTED`.

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

**`totalCents` is computed in the constructor, never accepted from the client.** If the client sent the
total, a user could book 10 seats for 1 cent. **Never trust a client with money arithmetic** — recompute
it server-side from values you fetched yourself. This is one of the most commonly exploited bugs in
real e-commerce code.

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

**The ownership check lives in `cancel`, inside the aggregate.** It could sit in the controller with
`@PreAuthorize`, but here it is impossible to bypass — including from the Kafka consumer that will call
this method in Phase 12, where there is no HTTP request and no `@PreAuthorize` to run.

**This is the difference between authorization at the edge and authorization in the domain.** Coarse
rules ("must be logged in", "must be ADMIN") belong at the edge. **Rules about *this specific object*
belong on the object.** Getting that split right is a senior-level instinct, and the giveaway question
is "can user A cancel user B's booking?" — a question the edge cannot answer.

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

**`@JsonIgnoreProperties(ignoreUnknown = true)` is the line that keeps these two services independent.**
Event-service sends twelve fields; we want six. Without this annotation, every field event-service adds
breaks booking-service at runtime — a coupling that only shows up in production.

**`status` is a `String`, not the `EventStatus` enum.** Deliberate. If event-service adds a
`POSTPONED` status, an enum here would throw on deserialisation and booking-service would fail on
perfectly valid data. A string degrades gracefully: unknown status simply is not `"PUBLISHED"`, so it is
not bookable. **Accept liberally at a service boundary.**

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

**An interface with no implementation, and it works** — concept A3. `name = "event-service"` is a
**Eureka lookup**, not a URL. Spring Cloud LoadBalancer resolves it to a live instance and round-robins
between them. Everything Phase 1 built is being used by this one string.

`@PathVariable("id")` with the name spelled out: Feign does not always read parameter names from the
class file (it depends on the `-parameters` compiler flag), and the failure is a confusing runtime
error. **Always name them explicitly in Feign interfaces.**

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

**This class is not annotated `@Configuration`, on purpose.** A `@Configuration` class in a scanned
package becomes global. Feign configuration classes are referenced from `@FeignClient(configuration =
...)` and instantiated per client — so one client can have a different `ErrorDecoder` from another. Mark
it `@Configuration` and you accidentally apply it to everything, including clients you add next year.

**What this fixes:** without it, the call to event-service arrives with no `X-User-Id`, the
`HeaderAuthenticationFilter` builds no `Authentication`, and `@PreAuthorize("isAuthenticated()")`
returns 401. Also the correlation chain would end at the boundary (concept A5).

**The `instanceof` guard matters.** In a Kafka consumer or an `@Async` method there is no request, so
`getRequestAttributes()` returns null and the naive version throws an NPE. Phase 12 calls Feign from a
consumer, and this guard is what stops that being an incident.

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

**The pre-check is only a nicety.** `hasSeats()` reads a number that may be stale by the time
`reserveSeats` runs. It exists to give a friendly error in the common case. **The real check is inside
event-service's aggregate**, under its own transaction — which is why `reserveSeats` can still throw
after the pre-check passed. *Check for the message, constrain for the truth* — Phase 2a's pattern,
across a network this time.

**`create` is deliberately not `@Transactional`.** If it were, the database transaction would stay open
across two network calls — holding a connection from a pool of 10 for the entire round trip. Twenty
concurrent bookings and the pool is exhausted. **Never hold a database transaction open across a remote
call.** The narrow `persistConfirmed` is the transaction.

(`protected` + self-invocation means that inner `@Transactional` is actually **bypassed** here — the
AOP trap from the filters doc. In real code, move it to a separate bean. It is left visible so you can
spot it: this exact mistake is extremely common, and now you know how to see it.)

**The manual compensation is the punchline.** Look at what we had to write: catch, undo the remote call,
log a disaster if the undo also fails. That is a **compensating transaction**, hand-rolled — and it has
holes you cannot close:

- If booking-service **crashes** between reserve and persist, nothing runs the compensation at all.
- If `releaseSeats` fails, the seats are gone until a human notices.
- If the reserve call **timed out**, we do not know whether to compensate — releasing seats that were
  never reserved corrupts the count in the other direction (concept A4).

**Every one of these holes is why Phase 11 (outbox) and Phase 12 (saga) exist.** The saga makes the
compensation *durable and retryable* instead of a `catch` block that only runs if the process is still
alive.

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

**`getForUser` throws "not found", not "forbidden", when the booking belongs to someone else.** That is
deliberate: a 403 would confirm the reference exists, letting an attacker enumerate valid booking
references. Same reasoning as Phase 2a's identical login error messages.

**`cancel` has a second dual-write problem, in the opposite direction.** The booking is cancelled
locally; if the release call fails, seats stay reserved for a booking that no longer exists. The
`catch` swallows it because failing the cancel would be worse for the user — they would be stuck with a
booking they cannot get rid of. **Both choices are bad, which is the signal that the design is wrong,
not the error handling.** Phase 12 fixes it with an event that is retried until it succeeds.

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

**`@PreAuthorize` on the class, not per method** — every booking endpoint needs a user, with no
exceptions. A class-level rule cannot be forgotten when someone adds a fifth endpoint next month.
Fail-safe by default, exactly like the gateway's allow-list.

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

**Watch the booking-service log.** With `loggerLevel: full` you see the outgoing request, the forwarded
`X-Correlation-Id` and `X-User-Id`, and the response. Then grep that correlation id in the event-service
log: **one id, three processes.** Phase 3's work paying off.

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

**That difference is the entire argument of Phase 7.** Multiply by 200 concurrent requests and the first
number is a blip while the second is an outage.

Note the response is a 500. It should be a **503 Service Unavailable** with `Retry-After` — a downstream
outage is not an internal server error. Add that mapping to your `GlobalExceptionHandler`.

### Drill 2 — event-service slow

Add a `Thread.sleep(5000)` to event-service's `getById` and restart.

The booking now fails after 2 seconds with a read timeout. **Slow is worse than down**: a dead service
refuses connections instantly, while a slow one holds your threads for the full timeout. This is why
timeouts are not optional and why bulkheads (Phase 7) exist — so one slow dependency cannot consume
every thread you have.

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

Sit with that for a minute. This is a *correct-looking* piece of code, with error handling, that
permanently corrupts inventory. The `catch` block only runs if the process survives — and processes do
not survive crashes, OOM kills, or a deploy at the wrong moment.

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

You will likely see **one 201 and four 409s**, and `available_seats = 0`. Correct — but *why* is it
correct? Not because of your pre-check, which every one of those five requests passed. Because
event-service's `reserveSeats` runs inside a transaction on a single row, and `@Version` from Phase 4 is
quietly rejecting the losers.

**Run it several times.** Under the right timing you may see an `OptimisticLockException` surface as a
500 rather than a clean 409. **Phase 8 is about making that behaviour deliberate and correct instead of
accidental.**

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Sharing a DTO class between services | Deploy coupling; a distributed monolith | Copy the shape you need |
| Default Feign timeouts (10 s / 60 s) | Thread pool exhaustion under load | 1–2 s connect, ≤3 s read |
| No `ErrorDecoder` | HTTP status codes leak into domain logic | Map to domain exceptions |
| No `RequestInterceptor` | 401s between services; broken tracing | Propagate the headers |
| `@Transactional` around a remote call | Connection pool exhausted | Narrow the transaction |
| Feign config class marked `@Configuration` | Applied to every client | Leave it unannotated |
| Retrying 4xx | Same failure, more load | Retry 5xx and timeouts only |
| Trusting a client-supplied total | Users book for 1 cent | Recompute server-side |
| Enum for another service's status field | Breaks when they add a value | `String` at the boundary |
| No `@JsonIgnoreProperties(ignoreUnknown)` | Breaks when they add a field | Add it |
| Assuming a timeout means "did not happen" | Duplicates or leaks | Idempotency (Phase 9) |
| Compensation only in a `catch` block | Leaks whenever the process dies | Durable saga (Phase 12) |

---

## Production considerations

- **Swap the default Feign client** for Apache HttpClient 5 or OkHttp — the default has no connection
  pooling, so every call opens a new TCP connection.
- **Circuit breaker on every Feign client** (Phase 7). Without it, one sick dependency takes you down.
- **`loggerLevel: basic` in production.** `full` prints tokens and bodies.
- **Return 503 with `Retry-After`** for downstream outages, not 500.
- **Never `@Transactional` across a remote call.**
- **Consider a service-to-service credential** so internal endpoints are not callable by end users
  (step 1's note).
- **Budget your timeouts top-down:** gateway 5 s → booking 3 s → event 1 s. A downstream timeout larger
  than your own is meaningless.
- **Watch for chatty calls.** One booking making five Feign calls is a design problem; batch or
  denormalise instead.
- **Contract tests** (Phase 13) so event-service cannot break booking-service without a failing build.

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

Next: **Resilience4j.** Timeout, retry with exponential backoff and jitter, circuit breaker, fallback,
bulkhead and rate limiter — and, more importantly, when each is the wrong tool. Retrying a broken
service is how you turn a small outage into a large one.

**Pre-reading for Phase 7:** cascading failure, and the thread-pool-exhaustion story behind circuit
breakers.
