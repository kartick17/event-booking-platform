# Phase 9 — Idempotency: making writes safe to repeat

**Time:** 3–4 hours
**Needs:** Phase 8 finished.
**Pre-reading:** HTTP method semantics — which methods are idempotent by definition, and why POST is
not.

This phase closes Phase 8's Drill 6, and it unlocks two things we deliberately postponed: safe retries
on writes (Phase 7) and correct Kafka consumers (Phase 12).

---

# PART A — Concepts

## A1. What idempotency means

**An operation is idempotent if doing it twice has the same effect as doing it once.**

```
x = 5            idempotent — run it a thousand times, x is 5
x = x + 1        NOT idempotent — every run changes the result
DELETE user 7    idempotent — after the first, it is already gone
INSERT booking   NOT idempotent — two rows
```

**Idempotent is not the same as "returns the same response."** `DELETE /users/7` returns 204 the first
time and 404 the second. Different responses, same **state**. State is what matters.

**Safe** is a different property again: a safe method changes nothing at all. `GET` is safe. Every safe
method is idempotent; the reverse is not true.

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

**There is no way to distinguish them from the client side. None.** This is not a gap in HTTP; it is a
property of unreliable networks — the Two Generals problem. You cannot make the network reliable, so
you make the *operation* safe to repeat.

### Everywhere a duplicate comes from

| Source | Example |
|---|---|
| User | double-click, refresh on a POST result |
| Client retry | mobile app retrying a timeout |
| Load balancer / proxy | some retry on connection failure |
| Your own retry | Resilience4j (Phase 7) |
| Message broker | Kafka at-least-once delivery (Phase 10) |
| Rebalance | a consumer crashes before committing its offset |

**Six independent sources.** In a system with retries and a message broker, duplicates are not an edge
case — they are the normal operating condition. Design for them.

## A3. Delivery guarantees

The vocabulary you need for Phase 10, and the reason idempotency comes first:

**At-most-once** — send it, never retry. Fast, and messages can be lost. Fine for metrics, unacceptable
for bookings.

**At-least-once** — retry until acknowledged. Nothing is lost, **duplicates happen**. This is what Kafka
gives you by default and what almost every real system uses.

**Exactly-once** — the message is processed once, no loss, no duplicates.

**"Exactly-once delivery" is not achievable** over an unreliable network — the same Two Generals result.
What *is* achievable is **exactly-once processing**: at-least-once delivery plus an idempotent consumer.

```
at-least-once delivery  +  idempotent consumer  =  effectively exactly-once
```

**That equation is the answer to a very common interview question**, and it is why this phase must come
before Kafka. Kafka's own "exactly-once semantics" is a transactional read-process-write within Kafka;
it does not extend to your database side effects.

## A4. Idempotency keys

**The client generates a unique id for the *intent* and sends it with the request.** The server records
which keys it has already handled.

```
POST /api/v1/bookings
Idempotency-Key: 7f3a91c2-4b8e-4d1a-9c3f-2e5b8a1d6f04
{"eventId": 1, "quantity": 2}
```

```
first request  → process it, store (key → result), return 201
same key again → do not process, return the stored 201
```

**The key must identify the intent, not the request.** One key per "book two seats for event 1", reused
across every retry of that intent. A new booking gets a new key. Generate it when the user opens the
form, not when the button is clicked — otherwise a double-click produces two keys and you are back where
you started.

This is exactly how Stripe, PayPal and Adyen do it. `Idempotency-Key` is the de facto standard header
name; there is an IETF draft standardising it.

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

**1. Two requests with the same key arrive at the same moment.** Both check, both find nothing, both
process. **A check-then-act race — Phase 8 all over again.** The fix is the same: a `UNIQUE` constraint
on the key. One `INSERT` wins; the loser gets a constraint violation and knows a sibling is in flight.

**2. The same key with a *different* body.** The client has a bug, or is reusing keys. Returning the
first response would be wrong — they asked for something else. Return **422 Unprocessable Entity** and
say the key was reused with different parameters. The `request_fingerprint` column exists for this.

**3. The first attempt crashed halfway.** The row sits at `IN_PROGRESS` forever and the key is
permanently poisoned. Fix: a short lease — if `IN_PROGRESS` is older than N seconds, allow a retake.

### Natural idempotency — usually better

Before building a key table, ask whether the data already contains a natural unique identity.

```sql
CONSTRAINT uk_one_booking_per_intent UNIQUE (user_id, event_id, client_reference)
```

If a business rule says "one active booking per user per event", **a unique constraint gives you
idempotency for free** — no extra table, no expiry job, enforced by the database.

**Prefer natural keys when they exist.** Use the idempotency-key table when the same intent may
legitimately be repeated later (a user genuinely booking the same event twice), which is our case.

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

**The `UNIQUE (idempotency_key, user_id)` constraint is the mechanism, not a nicety.** It is what makes
concurrent duplicates safe: two simultaneous inserts, one wins, one gets a
`DataIntegrityViolationException`. Concept A4, case 1.

**Scoped by user**, so one user cannot guess another's key and read their response — a small but real
information leak if you skip it.

**`expires_at` plus its index**, because this table grows forever otherwise, and the cleanup job needs
that index to stay fast (Phase 2b said the same about refresh tokens).

`response_body TEXT` stores the original JSON so a replay is byte-identical. `booking_ref` is
denormalised for convenience — a replay can rebuild the response from the booking if you prefer that
over storing JSON.

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
claims the key, so a concurrent request with the same key hits the unique constraint instead of
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

**`isStale` is concept A4, case 3.** If the process died between claiming the key and completing, the
row is stuck `IN_PROGRESS`. Without this, that key is poisoned forever and the user can never complete
their booking. A 60-second lease means a crashed attempt self-heals.

**`FAILED` and `COMPLETED` are kept separate on purpose.** A failed attempt should be **retryable** —
the booking did not happen, so let them try again. Only successes are replayed. Storing an error
response and replaying it forever would be a bug: a transient failure would become permanent for that
key.

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

`payload.toString()` works because our request DTOs are **records**, whose generated `toString` includes
every component in a stable order. For a class, you would serialise to canonical JSON instead —
`toString` on a plain class is an object hash and would change every run, breaking replay entirely.

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

The `findBy...` above it is an optimisation for the common case. It is **not** the safety mechanism,
because two concurrent requests can both find nothing. The `UNIQUE (idempotency_key, user_id)`
constraint is what actually serialises them: one `INSERT` succeeds, the other throws.

**This is Phase 2a's pattern for the third time:** *check in code for a good message, constrain in the
database for correctness.* Once you see it in three different contexts it stops being a rule and becomes
an instinct.

**`saveAndFlush`, not `save`.** `save` only queues the insert; the constraint violation would then
surface at commit time, outside this `try`. Flushing forces the SQL now, so the exception lands where we
can handle it. **A genuinely easy mistake with a confusing symptom.**

**`REQUIRES_NEW` suspends the caller's transaction and runs this in its own.** That matters: the claim
must be committed *independently*, so that if the booking work later rolls back, the key record still
exists to record the failure. Sharing a transaction would roll the claim back too, and the whole
mechanism would evaporate exactly when it is needed.

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

The `catch` marks the record `FAILED` rather than deleting it, which preserves the fingerprint. A retry
with the same key and the same body is allowed through; the same key with a *different* body is still
correctly rejected as reuse.

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

**Requiring the header is a deliberate choice with a real cost:** it is a breaking API change for any
existing client. The alternatives are to make it optional (and lose the guarantee for clients that
forget) or to version the endpoint. **For a payment or booking API, requiring it is correct** —
Stripe requires it for exactly this reason. Say the trade-off out loud rather than pretending it is
free.

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

**In production this needs a distributed lock** (Phase 8, step 6) or ShedLock, or all three instances
run it simultaneously. Here the work is idempotent — deleting an already-deleted row is harmless — so
the duplication is wasteful rather than wrong. **Notice that idempotency is what makes the duplicate
run safe.** The concept applies to your own jobs, not just to user requests.

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

**A failed attempt must not poison the key**, or a transient downstream blip would permanently block
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

Now book with `Idempotency-Key: stuck-key`. It **succeeds**, and the log says *Reclaiming stale
idempotency key*.

Now set `created_at` to `NOW()` and try again: `409`, because a sibling request is genuinely in flight.

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
| Check-then-insert with no unique constraint | Concurrent duplicates still get through | `UNIQUE (key, user_id)` |
| `save` instead of `saveAndFlush` | The constraint violation escapes your `try` | Flush to force the SQL |
| Key claim in the caller's transaction | Rolls back with the work; mechanism disappears | `REQUIRES_NEW` |
| No request fingerprint | Same key, different data returns the wrong response | Hash the body |
| Replaying `FAILED` responses | A transient error becomes permanent | Replay only `COMPLETED` |
| No lease on `IN_PROGRESS` | A crash poisons the key forever | Stale reclaim |
| Keys not scoped by user | One user reads another's response | Include `user_id` |
| No expiry or cleanup | The table grows without bound | TTL + indexed cleanup job |
| Fingerprinting a non-record class | `toString` differs every run; replay never matches | Canonical JSON |
| Dedup row committed separately from the work | Message marked done, work never happened | One transaction |
| Server-generated keys | Retries get different keys; no protection | The client owns the key |
| One key per click | A double-click makes two keys | One key per **intent** |

---

## Production considerations

- **Document the header.** Clients must know to send it, keep it across retries, and generate a new one
  per intent.
- **Store the response body**, not just "done" — a replay must return the original answer, not a
  reconstruction that may have drifted.
- **Consider Redis** for the key store if the write rate is very high — with the same unique-claim
  semantics (`SET NX`) and a TTL. The database is simpler and transactional, which is why we used it.
- **Metrics:** replay rate, conflict rate, stale reclaims. A rising replay rate means clients are
  retrying, which means something upstream is timing out.
- **Prefer a natural unique constraint** where the business allows it (concept A4). Less machinery.
- **Idempotency does not replace locking.** Phase 8 solves "two users, one seat"; Phase 9 solves "one
  user, two requests". **You need both**, and knowing which problem you are looking at is the skill.
- **Apply it to consumers too** (step 8) — that table is load-bearing for Phase 12.

---

## Phase 9 checklist

- [ ] The same key twice returns the same booking reference with `Idempotent-Replay: true`
- [ ] Only one booking row and one seat deduction exist
- [ ] The concurrent double-click produces one booking and one 409
- [ ] The same key with different data is rejected
- [ ] A failed attempt can be retried with the same key
- [ ] A stale `IN_PROGRESS` record is reclaimed after the lease
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
