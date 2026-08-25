# Phase 13 — Testing: unit, slice, and integration with Testcontainers

**Time:** 7–8 hours
**Needs:** Phase 12 finished.
**Pre-reading:** the test pyramid; why Testcontainers beats an in-memory H2 database.

Every failure drill you ran by hand disappears the moment someone changes a line. This phase turns them
into tests that run on every build.

---

# PART A — Concepts

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

**Push tests down the pyramid.** A rule that can be tested with a plain unit test should not need
Postgres. **Our Phase 4 aggregate design pays off here:** `Event.reserveSeats()` is testable with `new
Event(...)` and no framework at all.

**The inverted pyramid** — mostly slow integration tests — is the most common testing failure. Builds
take twenty minutes, so developers stop running them, so bugs reach main.

## A2. Why H2 lies to you

The tempting shortcut: an in-memory H2 database for tests. It is fast, and it is wrong.

**What H2 cannot tell you about Postgres:**

- `FOR UPDATE SKIP LOCKED` — Phase 11 depends on it. H2's support differs.
- Partial indexes (`WHERE published_at IS NULL`) — Phase 11's key performance decision.
- `TIMESTAMPTZ` semantics, `JSONB`, arrays, `ON CONFLICT`.
- Real MVCC behaviour and lock timing — **Phase 8's entire subject**.
- Sequence and identity behaviour, actual constraint error messages.

**Testing against a database you do not deploy tests the wrong thing.** The bugs that reach production
are precisely the ones in the gap between H2 and Postgres.

**Testcontainers** runs real Postgres, Redis and Kafka in Docker for the test's lifetime. Slower to
start, and it tests what you actually ship. **For a project whose hard parts are locking, partial
indexes and Kafka semantics, there is no alternative.**

## A3. Test doubles, and what not to mock

| Type | What it does |
|---|---|
| **Dummy** | passed but never used |
| **Stub** | returns canned answers |
| **Spy** | a real object that records calls |
| **Mock** | pre-programmed with expectations, verified |
| **Fake** | a working lightweight implementation (an in-memory repository) |

**The rule: do not mock what you do not own.**

Mocking `KafkaTemplate` or a JDBC driver encodes *your belief* about how it behaves. When your belief is
wrong, the test passes and production fails. Mock **your own interfaces** (`EventGateway`,
`OutboxWriter`); use real instances or Testcontainers for third-party libraries.

**Corollary: mocks test interactions, not behaviour.** `verify(repo).save(any())` proves a method was
called, not that anything correct happened. Over-mocked tests pass while the system is broken, and they
break on every harmless refactor.

## A4. Testing asynchronous code

Phase 12's saga takes 300–600 ms and spans two services. `Thread.sleep(1000)` in a test is the classic
mistake: too short and it is flaky, too long and your suite crawls.

**Use Awaitility** — poll until a condition holds, with a timeout:

```java
await().atMost(Duration.ofSeconds(10))
       .pollInterval(Duration.ofMillis(200))
       .untilAsserted(() -> assertThat(repo.findByRef(ref).getStatus()).isEqualTo(CONFIRMED));
```

Returns as soon as it is true, fails fast if it never is. **Fast when it works, clear when it does
not.**

**Flaky tests are worse than no tests.** A suite that fails randomly gets ignored, and then a real
failure gets ignored too. Root causes: fixed sleeps, shared state between tests, dependence on test
order, real clocks.

## A5. Contract testing

Phase 6's warning: booking-service copies `EventDto` and does not share a class with event-service. Good
for decoupling, but **nothing stops event-service renaming a field and breaking booking-service** with
both builds green.

**Contract testing** fixes that without recoupling:

```
consumer (booking-service) writes:  "given event 1 exists, GET /events/1 returns id, title, priceCents"
   → a contract file
provider (event-service) build:      replays the contract against the real controller
   → provider build FAILS if it no longer satisfies it
```

**The consumer states its needs; the provider's build verifies them.** Renaming `priceCents` now breaks
event-service's build, at the right time, in the right repository.

Tools: **Spring Cloud Contract** (Groovy/YAML contracts, JVM-focused) and **Pact** (cross-language, a
broker for sharing contracts).

**Understand the concept even if you never write one** — "how do you stop one service breaking another?"
is a standard interview question, and "contract tests" is the answer that shows you have thought about
it.

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

**These tests are only possible because the rules live in the aggregate** (Phase 4). If `reserveSeats`
were logic inside a service that called a repository, every one of these would need mocks or a database.
**Testability is the practical measure of good domain design**, and this file is the evidence.

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

**Three of these five tests are security tests**, and they encode Phase 4's authorization exactly:
public read, authenticated write, ADMIN-only create. **Authorization rules are the easiest thing to
break accidentally and the most expensive to get wrong** — they deserve tests more than most business
logic.

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

**These three tests cover Phase 11's real mechanics:** the unpublished filter, the backoff schedule, and
the unique constraint that makes dedup work. **All three depend on Postgres behaviour** and none would
be meaningfully tested against H2.

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

**`await().during(...)` in the duplicate test is the subtle one.** Asserting once proves nothing about a
duplicate that has not arrived yet; holding the assertion for three seconds proves the second message
was genuinely ignored rather than merely slow. **Testing that something does *not* happen requires
time**, and this is how you do it without a `sleep`.

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

**`CountDownLatch` as a start gate is the technique to remember.** Threads submitted in a loop start at
slightly different times, which hides races. The latch holds all 100 until they are ready, then releases
them together — maximum contention, reproducible.

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

**`group-id: test-${random.uuid}`** gives each run a fresh consumer group, so it reads from the
beginning and does not inherit offsets from a previous run. Shared group ids across test runs cause the
most baffling flakiness in Kafka testing.

**Speed:** reuse containers across classes by declaring them `static` in an abstract base class that
tests extend. Testcontainers reuse (`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`)
keeps them alive between runs locally — a large win, and one to disable in CI.

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
| `@DataJpaTest` without `replace = NONE` | H2 silently substituted | Add the annotation |
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

## Production considerations

- **Run integration tests in CI on every PR.** Testcontainers needs Docker on the runner.
- **Keep unit tests under 10 seconds total** so people run them constantly.
- **Coverage is a smell detector, not a goal.** 100% coverage of getters says nothing. Untested
  compensation logic says a lot.
- **Test data builders** (`anEvent().withCapacity(5).published().build()`) keep tests readable as they
  multiply.
- **Contract tests** (concept A5) between event-service and booking-service, so a renamed field breaks
  the right build.
- **Chaos testing with Toxiproxy** — add latency or drop connections inside integration tests to verify
  Phase 7's circuit breakers actually behave as configured.
- **Load-test the booking path** before launch. Phase 8's bugs are invisible at low concurrency.
- **A regression test for every production bug**, always, before the fix.

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

**Architecture polish:** layered versus hexagonal, ports and adapters applied to one service, DDD
vocabulary (aggregate, value object, domain event) applied to what you already built, CQRS basics, and
architecture decision records so the reasoning survives you.

Plus the final review — the questions you should now be able to answer about any multi-service Spring
application.

**Pre-reading for Phase 14:** the ports-and-adapters diagram; aggregate root in one paragraph.
