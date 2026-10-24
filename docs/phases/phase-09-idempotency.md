# Phase 9 — Idempotency: making writes safe to repeat

**Time:** 3–4 hours
**Needs:** Phase 8 finished.
**Pre-reading:** HTTP method meaning and behaviour — which methods are idempotent by definition, and why POST is
not.

Phase 8 prevented competing seat updates from corrupting a count. This phase handles a different problem: **the same intended action arriving more than once**. It prepares for safer write retries and Kafka message handling.

---

## In plain English

This phase stops the same booking attempt from creating duplicate bookings. Requests can repeat because of a double-click, a retry, or a lost response.

Example: the server saves a booking, but the reply never reaches the client. The client tries again. With the same idempotency key, the server recognises the earlier attempt and returns its result instead of making another booking.

Words used in this phase:

- **Idempotent:** repeating the same action has the same final effect as doing it once.
- **Idempotency key:** a unique label for one intended action, reused for retries of that action.
- **Deduplication:** recognising and skipping work already handled.
- **Fingerprint:** a calculated value used to check whether two request bodies contain the same data.
- **Unique constraint:** a database rule that stops duplicate values or value combinations.
- **Lease:** a time limit for owning work marked as in progress.
- **At-most-once:** do not repeat delivery; work may be lost.
- **At-least-once:** work may be delivered again, so duplicates must be handled.
- **Exactly-once effect:** the business change happens once even if processing is attempted more than once.

Start by separating "one intended booking" from "one HTTP request." Several requests can represent the same intended booking.

---

# PART A — Understand the ideas

## A1. What idempotency means

**An operation is idempotent if doing it twice has the same effect as doing it once.**

```
x = 5            idempotent — run it a thousand times, x is 5
x = x + 1        NOT idempotent — every run changes the result
DELETE user 7    idempotent — after the first, it is already gone
INSERT booking   NOT idempotent — two rows
```

**The response can differ even when the action is idempotent.** `DELETE /users/7` may return 204, then 404 when repeated. The user is still deleted after either attempt. The final saved state is what matters.

**Safe** means the method does not request a business-data change. `GET` is safe. Deleting a user changes data, so it is not safe, even though repeating the same delete has the same final effect.

| Method | Safe | Idempotent | Why |
|---|---|---|---|
| GET | yes | yes | changes nothing |
| PUT | no | **yes** | sets the resource to a given state |
| DELETE | no | **yes** | already gone stays gone |
| POST | no | **no** | creates something new each time |
| PATCH | no | **depends** | `set status=X` yes; `increment by 1` no |

**`POST /api/v1/bookings` is not idempotent, and that is exactly our problem.**

## A2. Why this matters more in distributed systems than anywhere else

### The unknown outcome

This is the heart of it. Phase 6 raised it; here is the consequence:

```
client ──POST /bookings──▶ booking-service
                             booking created, committed
       ◀──── (timeout) ─✗    response lost in the network
```

**The client cannot tell these two cases apart:**
1. The request never arrived. → retrying is correct.
2. The request succeeded and the response was lost. → retrying creates a duplicate.

**A missing reply does not tell the client what happened on the server.** Rather than assuming the first attempt failed, give retries a way to identify the same action. This makes repeating the action safe even when the network is unreliable.

### Everywhere a duplicate comes from

| Source | Example |
|---|---|
| User | double-click, refresh on a POST result |
| Client retry | mobile app retrying a timeout |
| Load balancer / proxy | some retry on connection failure |
| Your own retry | Resilience4j (Phase 7) |
| Message broker | Kafka at-least-once delivery (Phase 10) |
| Rebalance | a consumer crashes before committing its offset |

These are several independent ways a request can repeat. Retries and message delivery make duplicates normal enough that the application must be designed to handle them.

## A3. Delivery guarantees

The vocabulary you need for Phase 10, and the reason idempotency comes first:

**At-most-once:** do not retry delivery. This avoids duplicate attempts, but a failed delivery can lose the work. That is unsuitable for a booking that must not disappear.

**At-least-once:** retry or deliver again when acknowledgement is missing. A request or message may repeat. The application must recognise already completed work.

**Exactly-once effect:** the intended change is applied once. This needs a clearly defined boundary, such as one database transaction, and protection against repeating the change.

**Do not assume a message will arrive only once.** In this project, we allow repeat delivery and make the consumer check whether it has already completed the work. The duplicate check and business change must be saved together for the database effect to happen once.

```
at-least-once delivery  +  idempotent consumer  =  effectively exactly-once
```

Kafka's exactly-once support applies to supported transactions within Kafka. It does not automatically include your application's database changes. That is why we still need consumer-side duplicate protection.

## A4. Idempotency keys

**The client gives one intended action a unique label.** It sends that label with every retry. The server records the label and the result so it can recognise later attempts.

```
POST /api/v1/bookings
Idempotency-Key: 7f3a91c2-4b8e-4d1a-9c3f-2e5b8a1d6f04
{"eventId": 1, "quantity": 2}
```

```
first request  → process it, store (key → result), return 201
same key again → do not process, return the stored 201
```

**Reuse a key only for the same intended booking.** Generate it when the form is opened, and keep it for retries and double-clicks. A genuinely new booking gets a new key. Generating a new key on every click would make a double-click look like two separate actions.

`Idempotency-Key` is a commonly used header name for this pattern. Payment APIs also use idempotency keys so a repeated request does not repeat the intended payment.

### What must be stored

| Column | Why |
|---|---|
| `idempotency_key` | the key, unique |
| `user_id` | scope keys per user — one user must not read another's cached response |
| `request_fingerprint` | hash of the body, to detect key reuse with different data |
| `status` | `IN_PROGRESS` / `COMPLETED` / `FAILED` |
| `response_status`, `response_body` | to replay the original answer |
| `created_at`, `expires_at` | keys cannot be kept forever |

### The three hard cases

**1. The same key arrives twice at once.** Both requests could check before either saves. A database `UNIQUE` constraint lets only one insert succeed. The other request learns that the action is already recorded or in progress.

**2. The same key arrives with different request data.** It no longer describes the same action. Return **422 Unprocessable Entity** rather than reuse the first result. The `request_fingerprint` stores a value used to compare the request data.

**3. The first process crashes while work is in progress.** An `IN_PROGRESS` record could stay forever. A lease gives it a time limit so the system can identify work that needs recovery. Recovering must still account for any business change that may already have happened.

### Natural idempotency — usually better

First ask whether an existing business identifier can prevent duplicates. You may not need a separate key table.

```sql
CONSTRAINT uk_one_booking_per_intent UNIQUE (user_id, event_id, client_reference)
```

If the business allows only one active booking for a given user and event, a matching unique constraint can prevent a second active record. You still need to handle the duplicate attempt and return the appropriate result.

Use an existing unique business identifier where it fits the rules. Use a separate idempotency key when the user may legitimately make another booking for the same event later, as in our project.

---

# PART B — Build it

## Step 1 — The table

**File:** `services/booking-service/src/main/resources/db/migration/V2__create_idempotency_keys.sql`

```sql
CREATE TABLE idempotency_keys (
    id                   BIGSERIAL PRIMARY KEY,
    idempotency_key      VARCHAR(100) NOT NULL,
    user_id              VARCHAR(255) NOT NULL,
    endpoint             VARCHAR(200) NOT NULL,
    request_fingerprint  VARCHAR(64)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    response_status      INTEGER,
    response_body        TEXT,
    booking_ref          VARCHAR(20),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key, user_id)
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);
```

**`UNIQUE (idempotency_key, user_id)` prevents two records for the same user and key.** If two inserts overlap, only one succeeds; the other gets a `DataIntegrityViolationException`. A Java check before the insert is not enough.

**Scoped by user**, so one user cannot guess another's key and read their response — a small but real
information leak if you skip it.

**`expires_at` plus its index**, because this table grows forever otherwise, and the cleanup job needs
that index to stay fast (Phase 2b said the same about refresh tokens).

`response_body TEXT` keeps the original JSON so the same result can be returned on a retry. `booking_ref` also records which booking was created. An alternative is to rebuild the response from that booking rather than store the JSON.

## Step 2 — Entity

**File:** `.../domain/IdempotencyRecord.java`

```java
package com.eventbooking.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    public enum Status { IN_PROGRESS, COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 200)
    private String endpoint;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "booking_ref", length = 20)
    private String bookingRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String idempotencyKey, String userId, String endpoint,
                             String requestFingerprint, Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.endpoint = endpoint;
        this.requestFingerprint = requestFingerprint;
        this.expiresAt = expiresAt;
        this.status = Status.IN_PROGRESS;
    }
```

**A record starts as `IN_PROGRESS`, written *before* the work begins.** That ordering is deliberate: it
claims the key, so a happening at the same time request with the same key hits the unique constraint instead of
processing in parallel.

**Then the completion methods:**

```java
    public void complete(int responseStatus, String responseBody, String bookingRef) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.bookingRef = bookingRef;
    }

    public void fail() {
        this.status = Status.FAILED;
    }

    public boolean isReplayable() {
        return status == Status.COMPLETED && expiresAt.isAfter(Instant.now());
    }

    public boolean isStale(Duration leaseDuration) {
        return status == Status.IN_PROGRESS
                && createdAt.isBefore(Instant.now().minus(leaseDuration));
    }

    public boolean matches(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }
```

(Add getters.)

**`isStale` finds old `IN_PROGRESS` claims.** A crash may leave a key claimed without a final result. The 60-second lease lets recovery identify an abandoned attempt. It is still necessary to check whether any business work already completed before deciding what to retry.

**Keep `FAILED` separate from `COMPLETED`.** A temporary failure should allow a later attempt when it is safe. Completed attempts return the saved result. Do not make a temporary error permanent by returning it forever for that key.

## Step 3 — Repository

**File:** `.../infrastructure/IdempotencyRepository.java`

```java
package com.eventbooking.booking.infrastructure;

import com.eventbooking.booking.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKeyAndUserId(String key, String userId);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
```

## Step 4 — The idempotency service

**File:** `.../application/IdempotencyService.java`

Class shell and the fingerprint:

```java
package com.eventbooking.booking.application;

import com.eventbooking.booking.domain.IdempotencyRecord;
import com.eventbooking.booking.exception.IdempotencyConflictException;
import com.eventbooking.booking.infrastructure.IdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Duration KEY_TTL = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofSeconds(60);

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    public String fingerprint(Object payload) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(payload.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot fingerprint request", ex);
        }
    }
```

Our request DTOs are Java records. Their generated `payload.toString()` includes all components in a defined order, so this example uses it for the fingerprint input. For an ordinary class, use a stable JSON representation instead: the default `toString` is not a reliable description of the request data.

**24-hour TTL:** long enough for any realistic client retry, short enough that the table stays small.
Stripe uses 24 hours too.

**Now claiming a key** — the most important method in the phase:

```java
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord claim(String key, String userId, String endpoint, String fingerprint) {

        Optional<IdempotencyRecord> existing =
                repository.findByIdempotencyKeyAndUserId(key, userId);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();

            if (!record.matches(fingerprint)) {
                throw new IdempotencyConflictException(
                        "This Idempotency-Key was already used with different request data");
            }
            if (record.isReplayable()) {
                return record;                       // caller replays the stored response
            }
            if (record.isStale(LEASE)) {
                log.warn("Reclaiming stale idempotency key {} for user {}", key, userId);
                return record;                       // previous attempt died; take it over
            }
            if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
                throw new IdempotencyConflictException(
                        "A request with this Idempotency-Key is already being processed");
            }
            // FAILED → allow a fresh attempt on the same row
            return record;
        }

        try {
            return repository.saveAndFlush(new IdempotencyRecord(
                    key, userId, endpoint, fingerprint, Instant.now().plus(KEY_TTL)));

        } catch (DataIntegrityViolationException ex) {
            // another request claimed the same key between our SELECT and our INSERT
            throw new IdempotencyConflictException(
                    "A request with this Idempotency-Key is already being processed");
        }
    }
```

**Read the `catch` block first — it is the reason this works.**

**The earlier `findBy...` check saves work in the usual case.** It is not the duplicate protection: two requests can both find nothing. The `UNIQUE (idempotency_key, user_id)` database rule lets only one matching `INSERT` succeed.

**The same rule appears again:** check in Java for a helpful message, then use a database constraint to protect against overlapping requests.

**`saveAndFlush` sends SQL now.** A normal `save` can delay the insert until commit, so the uniqueness error may occur after this `try` block. Flushing makes that error appear where this example expects to handle it.

**`REQUIRES_NEW` starts a separate transaction.** The key claim must stay recorded even if the later booking transaction fails. Sharing the booking transaction would roll the claim back too, leaving no saved attempt to mark as failed.

**Then completion:**

```java
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long recordId, int status, String body, String bookingRef) {
        repository.findById(recordId)
                .ifPresent(record -> record.complete(status, body, bookingRef));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long recordId) {
        repository.findById(recordId).ifPresent(IdempotencyRecord::fail);
    }
```

Both `REQUIRES_NEW`, for the same reason: `markFailed` is called from a `catch` block while the
surrounding transaction is rolling back. Joining that transaction would roll the failure marker back
with it.

## Step 5 — Wire it into booking creation

**File:** `.../application/BookingService.java`

```java
    public BookingResult createIdempotent(CreateBookingRequest request,
                                          String userId,
                                          String idempotencyKey) {

        String fingerprint = idempotencyService.fingerprint(request);

        IdempotencyRecord record = idempotencyService.claim(
                idempotencyKey, userId, "POST /api/v1/bookings", fingerprint);

        if (record.isReplayable()) {
            log.info("Replaying idempotent response for key {}", idempotencyKey);
            return BookingResult.replay(record.getResponseStatus(), record.getResponseBody());
        }

        try {
            Booking booking = create(request, userId);
            String body = objectMapper.writeValueAsString(BookingResponse.from(booking));

            idempotencyService.complete(record.getId(), 201, body, booking.getBookingRef());
            return BookingResult.created(booking);

        } catch (Exception ex) {
            idempotencyService.markFailed(record.getId());
            throw ex;
        }
    }
```

**The shape to remember: claim → replay-or-work → record the outcome.** Every idempotent endpoint you
ever write has this structure.

The `catch` marks the record `FAILED` and keeps its fingerprint. This allows a retry with the same key and body, while still rejecting the same key used for different data.

**File:** `.../api/dto/BookingResult.java`

```java
package com.eventbooking.booking.api.dto;

import com.eventbooking.booking.domain.Booking;

public record BookingResult(int status, String rawBody, Booking booking, boolean replayed) {

    public static BookingResult created(Booking booking) {
        return new BookingResult(201, null, booking, false);
    }

    public static BookingResult replay(int status, String rawBody) {
        return new BookingResult(status, rawBody, null, true);
    }
}
```

## Step 6 — Controller

**File:** `.../api/BookingController.java`

```java
    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request,
            Authentication authentication) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException(
                    "Idempotency-Key header is required for booking creation");
        }

        BookingResult result = bookingService.createIdempotent(
                request, authentication.getName(), idempotencyKey);

        if (result.replayed()) {
            return ResponseEntity.status(result.status())
                    .header("Idempotent-Replay", "true")
                    .header("Content-Type", "application/json")
                    .body(result.rawBody());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replay", "false")
                .body(BookingResponse.from(result.booking()));
    }
```

**Requiring the header changes the client agreement.** Existing clients must start sending it. Making it optional leaves callers without it unprotected; a new endpoint version is another option. This booking API chooses to require it, so document that requirement clearly.

**`Idempotent-Replay: true` on a replayed response** so clients and your own logs can tell a replay from
a fresh creation. Without it, debugging "why did this only create one booking?" is guesswork.

**Map the exceptions** in `GlobalExceptionHandler`:

```java
    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ResponseEntity<ApiError> handleMissingKey(MissingIdempotencyKeyException ex,
                                                     HttpServletRequest request) {
        return ResponseEntity.badRequest().body(
                ApiError.of(400, "Bad Request", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex,
                                                              HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }
```

**409 with `Retry-After` for the in-flight case.** The sibling request is still working; retrying in a
second will find a `COMPLETED` record and get the real response.

Strictly, key-reuse-with-different-body deserves **422 Unprocessable Entity** (concept A4, case 2) —
the request is understood but semantically wrong. Splitting the two exceptions is a good exercise.

## Step 7 — Cleanup

**File:** `.../application/IdempotencyCleanupJob.java`

```java
package com.eventbooking.booking.application;

import com.eventbooking.booking.infrastructure.IdempotencyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyRepository repository;

    public IdempotencyCleanupJob(IdempotencyRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void deleteExpiredKeys() {
        int deleted = repository.deleteExpired(Instant.now());
        if (deleted > 0) {
            log.info("Idempotency cleanup removed {} expired keys", deleted);
        }
    }
}
```

Add `@EnableScheduling` to the main class.

**The `expires_at` index from V1 is what makes this cheap.** Without it, this job scans the whole table
every night and gets slower forever — a job that was fine at launch and pages you at 3am eighteen months
later.

**`0 17 3` — 03:17, not 03:00.** Every scheduled job in the world runs at the top of the hour; offset
minutes spread the load. Small habit, real benefit.

**Several application instances may run this cleanup at once.** A distributed lock or ShedLock can limit it to one runner. Here, repeated deletion is harmless because already deleted rows stay deleted. Without a lock the extra runs waste work, but do not repeat a business change.

## Step 8 — Idempotent consumers, prepared for Phase 12

The same idea for messages. Build the table now.

**File:** `.../db/migration/V3__create_processed_messages.sql`

```sql
CREATE TABLE processed_messages (
    message_id   VARCHAR(100) PRIMARY KEY,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_messages_processed_at ON processed_messages (processed_at);
```

Add the same migration to **event-service** — both services will consume messages.

```java
@Service
public class MessageDeduplicator {

    private final ProcessedMessageRepository repository;

    @Transactional
    public boolean alreadyProcessed(String messageId, String consumer) {
        if (repository.existsById(messageId)) {
            return true;
        }
        try {
            repository.saveAndFlush(new ProcessedMessage(messageId, consumer));
            return false;
        } catch (DataIntegrityViolationException ex) {
            return true;      // a concurrent consumer won the race
        }
    }
}
```

**Same pattern, third context: check for speed, constrain for correctness.**

**The critical detail for Phase 12:** this insert must happen **in the same transaction as the business
work**. If they are separate:

```
mark processed  ✓ committed
business work   ✗ failed
→ the message is never retried. The work never happens. Silent data loss.
```

One transaction means both succeed or both roll back, and a rolled-back message is redelivered and
retried. **This single detail is what makes "at-least-once + idempotent consumer = effectively
exactly-once" actually true** rather than just a slogan.

## Step 9 — Run it

```bash
KEY=$(uuidgen)

# first request
curl -s -i -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"eventId":1,"quantity":2}' | grep -E "HTTP|Idempotent-Replay|bookingRef"

# same key again
curl -s -i -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"eventId":1,"quantity":2}' | grep -E "HTTP|Idempotent-Replay|bookingRef"
```

```
HTTP/1.1 201
Idempotent-Replay: false
"bookingRef":"BK-7F3A91C2"

HTTP/1.1 201
Idempotent-Replay: true
"bookingRef":"BK-7F3A91C2"          ← the same reference
```

**Same booking reference. One row. One set of seats.**

```bash
docker exec -it ebp-postgres psql -U ebp -d booking_db \
  -c "SELECT count(*) FROM bookings WHERE booking_ref = 'BK-7F3A91C2';"
docker exec -it ebp-postgres psql -U ebp -d event_db \
  -c "SELECT available_seats FROM events WHERE id = 1;"
```

One booking, and seats reduced **once**.

---

## Break it on purpose

### Drill 1 — the double-click, finally fixed

Phase 8's Drill 6, now with a key:

```bash
KEY=$(uuidgen)
for i in 1 2; do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/v1/bookings \
    -H "Authorization: Bearer $USER_TOKEN" -H "Idempotency-Key: $KEY" \
    -H "Content-Type: application/json" -d '{"eventId":1,"quantity":1}' &
done; wait; echo

docker exec -it ebp-postgres psql -U ebp -d booking_db \
  -c "SELECT count(*) FROM bookings WHERE created_at > NOW() - INTERVAL '1 minute';"
```

Typically `201 409` — and **one** booking.

The 409 is the loser of the unique-constraint race (concept A4, case 1). A well-behaved client sees 409
with `Retry-After`, retries, and gets the replayed 201. **Phase 8's unfixable bug is now fixed** — and
notice it took a completely different mechanism from locking, because it was a completely different
problem.

### Drill 2 — the same key with different data

```bash
KEY=$(uuidgen)
curl -s -o /dev/null -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" -d '{"eventId":1,"quantity":1}'

curl -i -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Idempotency-Key: $KEY" \
  -H "Content-Type: application/json" -d '{"eventId":1,"quantity":5}'
```

`409` (or `422` if you split them): *already used with different request data.*

**Consider what the alternative would be.** Without the fingerprint, the second request would receive
the first response — a confirmation for 1 seat when they asked for 5, with a 201 status. **The user
believes they booked 5 seats. They did not.** The fingerprint turns a silent wrong answer into a loud
error.

### Drill 3 — retry after a genuine failure

Stop event-service. Book with a key → fails, record marked `FAILED`. Start event-service. Retry with the
**same** key → succeeds.

```bash
docker exec -it ebp-postgres psql -U ebp -d booking_db \
  -c "SELECT idempotency_key, status FROM idempotency_keys ORDER BY id DESC LIMIT 1;"
```

**A failed attempt must not poison the key**, or a temporary downstream blip would permanently block
that user's booking. This is why `FAILED` and `COMPLETED` are separate states.

### Drill 4 — the stale lease

Simulate a crash: insert an `IN_PROGRESS` row by hand with an old `created_at`.

```bash
docker exec -it ebp-postgres psql -U ebp -d booking_db -c \
"INSERT INTO idempotency_keys
   (idempotency_key, user_id, endpoint, request_fingerprint, status, created_at, expires_at)
 VALUES ('stuck-key','kartick@example.com','POST /api/v1/bookings','abc','IN_PROGRESS',
         NOW() - INTERVAL '5 minutes', NOW() + INTERVAL '1 day');"
```

Now book with `Idempotency-Key: stuck-key`. It **succeeds**, and the log says *Reclaiming out of date
idempotency key*.

Now set `created_at` to `NOW()` and try again: `409`, because a sibling request is really in flight.

**Without the lease, `stuck-key` would be dead forever.** Any mechanism that can get stuck needs a
timeout — the same lesson as Phase 8's lock TTL and Phase 1's Eureka lease. **This pattern repeats
everywhere in distributed systems: state that a crash can strand must expire on its own.**

### Drill 5 — retry on writes is now safe

Go back to Phase 7's `EventGateway` and add `@Retry` to a **booking creation** path that carries an
idempotency key. Force timeouts. Now duplicate requests collapse into one booking.

**This is the payoff.** Phase 7 said "never retry writes" — that was true *then*. With idempotency, the
rule becomes **"never retry non-idempotent writes"**, and you now control which side of that line your
writes are on.

**That progression is the actual lesson of Phases 7–9**, and it is a strong thing to be able to narrate
in an interview: timeouts → retries → duplicates → idempotency → safe retries.

### Drill 6 — missing key

```bash
curl -i -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
  -d '{"eventId":1,"quantity":1}'
```

`400`. Then decide, deliberately: is requiring the header right for your API? Stripe says yes for
payments. Some APIs generate a key server-side from `(user, endpoint, body-hash, 5-second window)` —
weaker, but no client change needed. **Both are defensible; drifting into one by accident is not.**

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Check-then-insert with no unique constraint | Happening at the same time duplicates still get through | `UNIQUE (key, user_id)` |
| `save` instead of `saveAndFlush` | The constraint violation escapes your `try` | Flush to force the SQL |
| Key claim in the caller's transaction | Rolls back with the work; mechanism disappears | `REQUIRES_NEW` |
| No request fingerprint | Same key, different data returns the wrong response | Hash the body |
| Replaying `FAILED` responses | A temporary error becomes permanent | Replay only `COMPLETED` |
| No lease on `IN_PROGRESS` | A crash poisons the key forever | Out of date reclaim |
| Keys not scoped by user | One user reads another's response | Include `user_id` |
| No expiry or cleanup | The table grows without bound | TTL + indexed cleanup job |
| Fingerprinting a non-record class | `toString` differs every run; replay never matches | Standard JSON |
| Dedup row committed separately from the work | Message marked done, work never happened | One transaction |
| Server-generated keys | Retries get different keys; no protection | The client owns the key |
| One key per click | A double-click makes two keys | One key per **intent** |

---

## Notes for a real running system

- **Document how clients use the header:** keep one key for retries of the same action and create a new key for a new action.
- **Save the response body** so the repeated request can receive the original result. Rebuilding it later could show changed data.
- **Consider Redis only if measurements justify it.** A key store there needs an atomic claim such as `SET NX` and expiry. The local database makes transaction handling simpler in this project.
- **Measure repeats, conflicts, and reclaimed old claims.** More repeats can indicate timeouts or client retries.
- **Use a natural unique business rule when it fits**, as discussed in A4. This can reduce extra key-handling code.
- **Keep both locking and duplicate protection.** Phase 8 handles different requests competing for seats; Phase 9 handles repeated attempts for the same action.
- **Apply duplicate checks to message consumers too.** The saved message-id table is required for the saga in Phase 12.

---

## Phase 9 checklist

- [ ] The same key twice returns the same booking reference with `Idempotent-Replay: true`
- [ ] Only one booking row and one seat deduction exist
- [ ] The happening at the same time double-click produces one booking and one 409
- [ ] The same key with different data is rejected
- [ ] A failed attempt can be retried with the same key
- [ ] A out of date `IN_PROGRESS` record is reclaimed after the lease
- [ ] The cleanup job deletes expired keys
- [ ] `processed_messages` exists in both services
- [ ] You can explain why the claim uses `REQUIRES_NEW`
- [ ] You can explain why the dedup insert must share the business transaction
- [ ] You can say why "exactly-once delivery" is impossible and what to build instead
- [ ] You can explain why locking could not fix the double-click

---

## What Phase 10 does next

Every synchronous problem is now handled: timeouts, retries, breakers, locks, idempotency. And the
architecture is still wrong — Phase 6's Drill 3 leaks seats whenever the process dies at the wrong
moment, and no amount of `try/catch` fixes that.

Next: **Kafka.** Phase 10a is concepts only — brokers, topics, partitions, offsets, consumer groups,
keys, ordering, acknowledgements, retention. No code. Phase 10b builds producers and consumers.

**Pre-reading for Phase 10a:** log-based messaging versus queue-based (Kafka versus RabbitMQ), in one
paragraph.
