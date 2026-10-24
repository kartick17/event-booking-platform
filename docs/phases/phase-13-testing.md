# Phase 13 — Testing: unit, slice, and integration with Testcontainers

**Time:** 7–8 hours
**Needs:** Phase 12 finished.
**Pre-reading:** the test pyramid; why Testcontainers beats an in-memory H2 database.

Manual checks are useful, but they do not run again by themselves after code changes. This phase turns important failure exercises into repeatable tests run by the build.

---

## In plain English

This phase turns the checks you ran by hand into tests the computer can run again. Tests should catch problems when someone changes the code later.

Example: a test starts with 1 seat, sends several booking attempts, and checks that only the allowed number succeeds. Another test delivers the same message twice and checks that the seat change happens once.

Words used in this phase:

- **Assertion:** a check that compares the actual result with the expected result.
- **Unit test:** checks a small part, often one object, without starting the whole application.
- **Integration test:** checks real parts working together, such as Java code and Postgres.
- **End-to-end test:** checks a complete flow across its main components.
- **Test double:** a replacement for a dependency during a test. A fake has a small working implementation; a stub supplies set answers; a mock can check calls.
- **Testcontainers:** a tool that starts real databases or brokers in Docker for tests.
- **Awaitility:** a tool that waits for an expected result, up to a chosen time limit.
- **Contract test:** checks that the data one service provides still matches what another service needs.
- **Regression test:** a test that prevents a fixed bug from returning.
- **CI:** automated checks run when code is pushed or reviewed.

Start with tests of the Event object's rules. Use broader tests when the behaviour depends on databases, messages, or several services working together.

---

# PART A — Understand the ideas

## A1. The pyramid, and what each level is for

```
        ╱╲          E2E            few, slow, brittle, high confidence
       ╱──╲         Integration    some, medium speed, real dependencies
      ╱────╲        Slice          more, fast, one Spring layer
     ╱──────╲       Unit           many, milliseconds, no Spring at all
```

| Level | Spring? | Real DB? | Speed | Answers |
|---|---|---|---|---|
| Unit | no | no | <10 ms | is this logic correct? |
| Slice (`@WebMvcTest`, `@DataJpaTest`) | partial | sometimes | ~1 s | is this layer wired correctly? |
| Integration (`@SpringBootTest` + Testcontainers) | full | yes | 5–30 s | does it work with real infrastructure? |
| E2E (all services running) | full | yes | minutes | does the whole system work? |

**Use the smallest test that proves the behaviour.** A seat rule can be checked with `Event.reserveSeats()` on an object made using `new Event(...)`. It does not need a running database or Spring application. The object design from Phase 4 makes this possible.

If nearly every test starts the whole system, the build becomes slow and harder to diagnose. Keep many quick tests for individual rules and a smaller number of broader tests for parts working together.

## A2. Why H2 lies to you

H2 is a convenient in-memory database, but it does not behave exactly like Postgres. A passing H2 test cannot prove that Postgres-specific queries and locks work.

**What H2 cannot tell you about Postgres:**

- **Locking queries:** Phase 11 uses `FOR UPDATE SKIP LOCKED`; another database may behave differently.
- **Partial indexes:** an index using `WHERE published_at IS NULL` is important to outbox performance.
- **Postgres types and SQL:** examples include `TIMESTAMPTZ`, `JSONB`, arrays, and `ON CONFLICT`.
- **Overlapping transactions:** Postgres row versions and lock timing matter to Phase 8.
- **Generated ids and errors:** sequences, identity values, and constraint failures can differ between databases.

**Test database-specific behaviour on the database you use.** Differences in SQL, locking, indexes, and types can make a test pass on H2 while the real application fails on Postgres.

**Testcontainers starts real tools in Docker for the test.** It takes longer than a plain unit test, but lets us verify the actual Postgres, Redis, and Kafka behaviour this project depends on.

## A3. Test doubles, and what not to mock

| Type | What it does |
|---|---|
| **Dummy** | passed but never used |
| **Stub** | returns canned answers |
| **Spy** | a real object that records calls |
| **Mock** | pre-programmed with expectations, verified |
| **Fake** | a working lightweight implementation (an in-memory repository) |

**Replace your own boundaries in small tests; check external behaviour with real tools where needed.**

A mock of `KafkaTemplate` or JDBC behaves as you tell it to. It cannot prove that real Kafka or Postgres works that way. Mock your own interfaces, such as `EventGateway` and `OutboxWriter`, for unit tests, and use integration tests to check the real libraries and tools.

**Checking a method call is not the same as checking a result.** `verify(repo).save(any())` only proves a save was requested. Also check the important business outcome. Tests tied to every internal call can break after harmless code changes without finding real bugs.

## A4. Testing asynchronous code

The saga finishes later, after messages cross services. A fixed `Thread.sleep(1000)` makes tests wait even when work is already done, and can still fail on a slower machine. Wait for the actual expected condition with a time limit.

**Use Awaitility** — poll until a condition holds, with a timeout:

```java
await().atMost(Duration.ofSeconds(10))
       .pollInterval(Duration.ofMillis(200))
       .untilAsserted(() -> assertThat(repo.findByRef(ref).getStatus()).isEqualTo(CONFIRMED));
```

Awaitility checks the condition repeatedly and returns when it becomes true. If it never becomes true within the time limit, the test fails.

**Avoid tests that pass or fail depending on timing.** Fixed sleeps, shared test data, dependence on test order, and real-time clock assumptions are common causes. Make failures repeatable so people can trust the test results.

## A5. Contract testing

Booking-service owns its own `EventDto`, which keeps its code separate from event-service. But event-service could still rename a JSON field and break that consumer. Separate successful builds do not prove the two services agree on the response format.

**A contract test records what the consumer needs and checks that the provider still supplies it:**

```
consumer (booking-service) writes:  "given event 1 exists, GET /events/1 returns id, title, priceCents"
   → a contract file
provider (event-service) build:      replays the contract against the real controller
   → provider build FAILS if it no longer satisfies it
```

**The consumer describes the expected fields; the provider tests its response against them.** Removing `priceCents` then fails event-service's build before the incompatible change is released.

Tools: **Spring Cloud Contract** (Groovy/YAML contracts, JVM-focused) and **Pact** (cross-language, a
broker for sharing contracts).

The main idea is simple: test the agreement between services, not just each service on its own. This helps catch changes that would break another service.

---

# PART B — Build it

## Step 1 — Dependencies

Every service:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

`starter-test` brings JUnit 5, AssertJ, Mockito and `MockMvc`. Testcontainers versions come from the
Spring Boot BOM.

## Step 2 — Unit tests for the aggregate

**File:** `src/test/java/com/eventbooking/event/domain/EventTest.java`

```java
package com.eventbooking.event.domain;

import com.eventbooking.event.exception.InsufficientSeatsException;
import com.eventbooking.event.exception.InvalidEventStateException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

class EventTest {

    private Event publishedEvent(int capacity) {
        Event event = new Event("Rock Night", "desc", "Arena",
                Instant.now().plus(30, ChronoUnit.DAYS), capacity, 250_000L, "admin@x.com");
        event.publish();
        return event;
    }

    @Nested
    class ReserveSeats {

        @Test
        void reduces_available_seats() {
            Event event = publishedEvent(100);

            event.reserveSeats(3);

            assertThat(event.getAvailableSeats()).isEqualTo(97);
            assertThat(event.bookedSeats()).isEqualTo(3);
        }

        @Test
        void rejects_more_than_available() {
            Event event = publishedEvent(2);

            assertThatThrownBy(() -> event.reserveSeats(3))
                    .isInstanceOf(InsufficientSeatsException.class)
                    .hasMessageContaining("2 seats left");

            assertThat(event.getAvailableSeats()).isEqualTo(2);
        }

        @Test
        void rejects_when_cancelled() {
            Event event = publishedEvent(100);
            event.cancel();

            assertThatThrownBy(() -> event.reserveSeats(1))
                    .isInstanceOf(InvalidEventStateException.class);
        }

        @Test
        void marks_sold_out_at_zero() {
            Event event = publishedEvent(5);

            event.reserveSeats(5);

            assertThat(event.isSoldOut()).isTrue();
            assertThat(event.getAvailableSeats()).isZero();
        }
    }

    @Nested
    class ReleaseSeats {

        @Test
        void never_exceeds_capacity_on_duplicate_release() {
            Event event = publishedEvent(10);
            event.reserveSeats(2);

            event.releaseSeats(2);
            event.releaseSeats(2);          // duplicate message

            assertThat(event.getAvailableSeats()).isEqualTo(10);
        }
    }

    @Nested
    class StatusTransitions {

        @Test
        void cannot_publish_a_cancelled_event() {
            Event event = publishedEvent(10);
            event.cancel();

            assertThatThrownBy(event::publish)
                    .isInstanceOf(InvalidEventStateException.class)
                    .hasMessageContaining("CANCELLED to PUBLISHED");
        }
    }
}
```

**No Spring. No database. No mocks. Runs in single-digit milliseconds.**

**These rules can be tested on a plain Event object.** Because `reserveSeats` lives there, the test does not need a controller, repository, or database. This shows the practical benefit of keeping business rules together.

**`never_exceeds_capacity_on_duplicate_release` is the most valuable test here.** It encodes the
idempotency decision from Phase 4 that Phase 12 depends on. Someone "simplifying" `Math.min` away later
gets a failing test that explains why it exists.

`@Nested` groups tests by behaviour, so the failure output reads
`EventTest$ReserveSeats > rejects_more_than_available`.

## Step 3 — Service tests with Mockito

**File:** `src/test/java/com/eventbooking/booking/application/BookingServiceTest.java`

```java
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private EventGateway eventGateway;
    @Mock private OutboxWriter outboxWriter;

    @InjectMocks private BookingService bookingService;

    @Test
    void creates_pending_booking_and_writes_outbox_event() {
        given(eventGateway.getEvent(1L)).willReturn(
                new EventDto(1L, "Rock Night", Instant.now().plus(10, DAYS), 100, 250_000L, "PUBLISHED"));
        given(bookingRepository.save(any(Booking.class))).willAnswer(inv -> inv.getArgument(0));

        Booking booking = bookingService.createPending(
                new CreateBookingRequest(1L, 2), "kartick@x.com");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getTotalCents()).isEqualTo(500_000L);

        then(outboxWriter).should().write(
                eq("Booking"), eq("1"), eq("BookingCreated"), eq("booking.events"), any());
    }

    @Test
    void rejects_booking_for_unpublished_event() {
        given(eventGateway.getEvent(1L)).willReturn(
                new EventDto(1L, "Draft", Instant.now().plus(10, DAYS), 100, 250_000L, "DRAFT"));

        assertThatThrownBy(() -> bookingService.createPending(
                new CreateBookingRequest(1L, 2), "kartick@x.com"))
                .isInstanceOf(EventNotBookableException.class);

        then(bookingRepository).shouldHaveNoInteractions();
        then(outboxWriter).shouldHaveNoInteractions();
    }
}
```

**We mock `EventGateway` — our own interface — not Feign or `KafkaTemplate`.** Concept A3.

**`assertThat(booking.getTotalCents()).isEqualTo(500_000L)`** is the test that matters most here: it
proves the server computes the price and does not trust the client (Phase 6). A regression would be a
security bug, and this line catches it.

**`shouldHaveNoInteractions()` on the failure path** proves nothing was written when validation failed —
verifying an *absence*, which assertions on return values cannot do.

**`willAnswer(inv -> inv.getArgument(0))`** makes the mock repository return what was saved, mimicking
JPA closely enough for this test without a database.

## Step 4 — Controller slice tests

**File:** `src/test/java/com/eventbooking/event/api/EventControllerTest.java`

```java
@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, HeaderAuthenticationFilter.class})
class EventControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private EventService eventService;

    @Test
    void list_is_public() throws Exception {
        given(eventService.listPublishedCached(any())).willReturn(
                new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk());
    }

    @Test
    void create_requires_authentication() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_requires_admin_role() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .header("X-User-Id", "user@x.com")
                        .header("X-User-Roles", "ROLE_USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_can_create() throws Exception {
        given(eventService.create(any(), eq("admin@x.com"))).willReturn(sampleEvent());

        mockMvc.perform(post("/api/v1/events")
                        .header("X-User-Id", "admin@x.com")
                        .header("X-User-Roles", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Rock Night"));
    }

    @Test
    void rejects_invalid_payload_with_field_errors() throws Exception {
        String bad = """
                {"title":"","venue":"X","startsAt":"2020-01-01T19:00:00Z","capacity":0,"priceCents":-5}
                """;

        mockMvc.perform(post("/api/v1/events")
                        .header("X-User-Id", "admin@x.com")
                        .header("X-User-Roles", "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.capacity").exists());
    }
}
```

**`@WebMvcTest` loads only the web layer** — controllers, filters, `@ControllerAdvice`, Jackson. No JPA,
no database, no Kafka. Starts in about a second.

**The `@Import` line is essential.** Without it, `@WebMvcTest` uses Spring Security's default
configuration and **your security tests test the wrong rules**. They would still pass, and they would be
meaningless.

**Three tests check access rules:** public reads, authenticated writes, and admin-only creation. They preserve the intended permissions from Phase 4 and catch changes that accidentally make an endpoint public or block allowed users.

`rejects_invalid_payload_with_field_errors` locks in the `ApiError` contract from Phase 2a. Clients
depend on that JSON shape.

## Step 5 — Repository tests with real Postgres

**File:** `src/test/java/com/eventbooking/event/infrastructure/OutboxRepositoryTest.java`

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OutboxRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private OutboxRepository outboxRepository;
    @Autowired private TestEntityManager entityManager;

    @Test
    void finds_only_unpublished_rows_in_id_order() {
        outboxRepository.save(published(1));
        OutboxEvent pending1 = outboxRepository.save(pending("EventCreated"));
        OutboxEvent pending2 = outboxRepository.save(pending("EventCancelled"));
        entityManager.flush();

        List<OutboxEvent> batch = outboxRepository.findBatchForPublishing(
                Instant.now(), PageRequest.of(0, 10));

        assertThat(batch).extracting(OutboxEvent::getId)
                .containsExactly(pending1.getId(), pending2.getId());
    }

    @Test
    void skips_rows_scheduled_for_the_future() {
        OutboxEvent backedOff = pending("EventCreated");
        backedOff.markFailed("kafka down");        // pushes next_attempt_at forward
        outboxRepository.save(backedOff);
        entityManager.flush();

        assertThat(outboxRepository.findBatchForPublishing(
                Instant.now(), PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void message_id_is_unique() {
        OutboxEvent first = pending("EventCreated");
        outboxRepository.saveAndFlush(first);

        OutboxEvent duplicate = pending("EventCreated");
        ReflectionTestUtils.setField(duplicate, "messageId", first.getMessageId());

        assertThatThrownBy(() -> outboxRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

**`@ServiceConnection` is the modern Testcontainers integration** (Spring Boot 3.1+): it wires the
container's JDBC URL, username and password into the context automatically. No
`@DynamicPropertySource`.

**`replace = NONE`** stops Spring from substituting an embedded database. Without it, `@DataJpaTest`
quietly swaps in H2 and **you are back to testing the wrong database** (concept A2).

**`static` container** — one Postgres for the whole test class, not one per test. Container startup is
the expensive part.

**These tests need real Postgres behaviour.** They check the unpublished-row filter, retry timing, and the unique constraint used for duplicate protection. H2 is not enough to verify the Postgres-specific parts.

## Step 6 — Full integration: the saga end to end

**File:** `src/test/java/com/eventbooking/event/SagaIntegrationTest.java`

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class SagaIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    @Test
    void booking_created_event_reserves_seats_and_publishes_reply() {
        Event event = eventService.create(new CreateEventRequest(
                "Test Event", null, "Venue", Instant.now().plus(10, DAYS), 10, 100L), "admin@x.com");
        eventService.publish(event.getId());

        EventEnvelope bookingCreated = EventEnvelope.of("BookingCreated", "corr-1",
                Map.of("bookingId", 1, "bookingRef", "BK-TEST01",
                       "eventId", event.getId(), "quantity", 3));

        kafkaTemplate.send("booking.events", String.valueOf(event.getId()), bookingCreated);

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Event reloaded = eventRepository.findById(event.getId()).orElseThrow();
                    assertThat(reloaded.getAvailableSeats()).isEqualTo(7);
                });
    }

    @Test
    void duplicate_message_does_not_reserve_twice() {
        Event event = createPublishedEvent(10);

        EventEnvelope envelope = EventEnvelope.of("BookingCreated", "corr-2",
                Map.of("bookingId", 2, "bookingRef", "BK-TEST02",
                       "eventId", event.getId(), "quantity", 2));

        kafkaTemplate.send("booking.events", String.valueOf(event.getId()), envelope);
        kafkaTemplate.send("booking.events", String.valueOf(event.getId()), envelope);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(eventRepository.findById(event.getId()).orElseThrow()
                        .getAvailableSeats()).isEqualTo(8));

        // hold the assertion for a while — a late duplicate would break it
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(eventRepository.findById(event.getId()).orElseThrow()
                        .getAvailableSeats()).isEqualTo(8));
    }

    @Test
    void sold_out_publishes_rejection_instead_of_failing() {
        Event event = createPublishedEvent(1);

        kafkaTemplate.send("booking.events", String.valueOf(event.getId()),
                EventEnvelope.of("BookingCreated", "corr-3",
                        Map.of("bookingId", 3, "bookingRef", "BK-TEST03",
                               "eventId", event.getId(), "quantity", 5)));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(outboxRepository.findAll())
                        .anyMatch(row -> row.getEventType().equals("SeatsRejected")));

        assertThat(eventRepository.findById(event.getId()).orElseThrow()
                .getAvailableSeats()).isEqualTo(1);
    }
}
```

**These three tests are Phase 12's Drills 1, 3 and the rejection path, automated.**

**Real Postgres, real Kafka, real Redis.** The dedup insert, the transaction boundary, the partition key,
the aggregate rules — all exercised as they run in production.

**`await().during(...)` keeps checking for a period of time.** One quick check could pass before the duplicate message is handled. Requiring the seat count to stay unchanged for three seconds gives the duplicate a chance to arrive. It tests that period; it does not prove the value can never change later.

**Container startup is 10–20 seconds**, so keep these tests few and meaningful. Everything cheaper
belongs at a lower level (concept A1).

## Step 7 — The concurrency test

**File:** `src/test/java/com/eventbooking/event/ConcurrencyIntegrationTest.java`

```java
    @Test
    void concurrent_reservations_never_oversell() throws Exception {
        Event event = createPublishedEvent(50);
        int threads = 100;

        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                startGate.await();
                try {
                    eventService.reserveSeatsAtomic(event.getId(), 1);
                    succeeded.incrementAndGet();
                } catch (InsufficientSeatsException ex) {
                    rejected.incrementAndGet();
                }
                return null;
            });
        }

        startGate.countDown();                       // release all threads at once
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Event reloaded = eventRepository.findById(event.getId()).orElseThrow();

        assertThat(succeeded.get()).isEqualTo(50);
        assertThat(rejected.get()).isEqualTo(50);
        assertThat(reloaded.getAvailableSeats()).isZero();
    }
```

**`CountDownLatch` releases the test workers together.** Starting them one by one can hide timing conflicts. The latch waits until they are ready, then lets all 100 begin, making overlapping updates easier to reproduce.

**This is Phase 8's oversell drill as a permanent test.** Assert exactly 50 and 50, never "at most 50" —
a weaker assertion would pass if the code accidentally rejected everything.

**Run it against `reserveSeatsNaive` and watch it fail** with more than 50 successes and negative seats.
That failure is the proof the test is worth having.

## Step 8 — Idempotency test

```java
    @Test
    void same_idempotency_key_creates_one_booking() {
        String key = UUID.randomUUID().toString();
        CreateBookingRequest request = new CreateBookingRequest(eventId, 2);

        Booking first = bookingService.createPendingIdempotent(request, "user@x.com", key);
        Booking second = bookingService.createPendingIdempotent(request, "user@x.com", key);

        assertThat(second.getBookingRef()).isEqualTo(first.getBookingRef());
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    void same_key_with_different_body_is_rejected() {
        String key = UUID.randomUUID().toString();

        bookingService.createPendingIdempotent(new CreateBookingRequest(eventId, 2), "user@x.com", key);

        assertThatThrownBy(() -> bookingService.createPendingIdempotent(
                new CreateBookingRequest(eventId, 5), "user@x.com", key))
                .isInstanceOf(IdempotencyConflictException.class);
    }
```

Phase 9's drills, automated. **`bookingRepository.count()` is the assertion that matters** — it proves
the *effect* happened once, not merely that the response looked the same.

## Step 9 — Test configuration and speed

**File:** `src/test/resources/application-test.yml`

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  kafka:
    consumer:
      auto-offset-reset: earliest
      group-id: test-${random.uuid}

logging:
  level:
    org.apache.kafka: WARN
    org.testcontainers: INFO
```

**Flyway runs in tests too**, so the migrations themselves are tested. A broken migration fails the build
instead of the deployment — and this is the *only* place your migrations get exercised before
production.

**`group-id: test-${random.uuid}` gives each test run its own consumer group.** Combined with the test's offset-reset settings, it avoids reusing a previous run's saved reading position. Shared test group ids can make tests miss messages unpredictably.

**Reduce startup time by sharing test containers where appropriate.** A `static` container on a common base class can be reused by several test classes. Local reuse through `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` can also keep containers between runs. Disable that local reuse option in CI and keep test data isolated.

---

## Make the tests fail on purpose

A test you have never seen fail is not a test. For each of these, break the code, watch the failure, then
revert.

| Break this | Expected failure |
|---|---|
| Remove `Math.min` from `releaseSeats` | `never_exceeds_capacity_on_duplicate_release` |
| Compute `totalCents` from a client field | `creates_pending_booking...` price assertion |
| Change `@PreAuthorize` to `isAuthenticated()` | `create_requires_admin_role` |
| Remove `@Valid` from the controller | `rejects_invalid_payload_with_field_errors` |
| Remove the dedup check in the consumer | `duplicate_message_does_not_reserve_twice` |
| Use `reserveSeatsNaive` | `concurrent_reservations_never_oversell` |
| Drop the `messageId` unique constraint | `message_id_is_unique` |
| Throw instead of publishing `SeatsRejected` | `sold_out_publishes_rejection_instead_of_failing` |

**Every row is a bug that reached production in some real system.** Watching each test catch it is what
converts "we have tests" into "we have tests that matter."

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| H2 instead of Postgres | Passes locally, fails in production | Testcontainers |
| `@DataJpaTest` without `replace = NONE` | H2 without a clear error substituted | Add the annotation |
| `@WebMvcTest` without importing security config | Security tests are meaningless | `@Import` your config |
| `Thread.sleep` in async tests | Flaky and slow | Awaitility |
| Asserting once for a duplicate test | Proves nothing | `await().during(...)` |
| Mocking `KafkaTemplate` / JDBC | Tests your assumptions, not reality | Mock only your own interfaces |
| Only `verify(...)` assertions | Passes while broken | Assert on state and effects |
| Shared mutable state between tests | Order-dependent failures | Fresh data per test |
| Same Kafka `group.id` across runs | Old offsets cause weird failures | Random group id |
| Container per test method | Suite takes forever | `static` containers |
| Testing only happy paths | Compensation paths break in incidents | Test failures explicitly |
| No test for a fixed bug | It comes back | A regression test per bug |

---

## Notes for a real running system

- **Run integration tests in CI for each pull request.** The test machine needs Docker for Testcontainers.
- **Keep unit tests quick**, aiming for under 10 seconds total, so they are easy to run often.
- **Coverage shows what ran, not whether the checks were useful.** Focus on important rules and failure paths rather than a percentage alone.
- **Use test-data builders** such as `anEvent().withCapacity(5).published().build()` to make setups clear.
- **Add contract tests** between event-service and booking-service to catch incompatible field changes.
- **Test slow and broken connections.** Toxiproxy can introduce delays or dropped connections to check the Phase 7 behaviour.
- **Test under load.** Conflicting bookings may only appear with overlapping requests.
- **Add a regression test for a discovered bug** so a later change cannot quietly bring it back.

---

## Phase 13 checklist

- [ ] Aggregate rules are unit-tested with no Spring
- [ ] Service tests mock only your own interfaces
- [ ] Controller tests cover public / authenticated / admin-only
- [ ] Repository tests run on real Postgres via Testcontainers
- [ ] The saga is tested end to end with real Kafka
- [ ] The duplicate-message test holds its assertion over time
- [ ] The concurrency test proves no oversell with 100 threads
- [ ] Idempotency tests assert the effect happened once
- [ ] Flyway migrations run in the test suite
- [ ] You broke the code and watched each test fail
- [ ] You can explain why H2 would have hidden real bugs
- [ ] You can explain contract testing and what problem it solves

---

## What Phase 14 does next

The system works and is tested. Now step back from it.

**Next, make the code structure easier to explain.** Phase 14 compares layers with ports and adapters, applies the design terms to your existing classes, separates read and write models where useful, and records the reasons for major choices.

Plus the final review — the questions you should now be able to answer about any multi-service Spring
application.

**Pre-reading for Phase 14:** the ports-and-adapters diagram; aggregate root in one paragraph.
