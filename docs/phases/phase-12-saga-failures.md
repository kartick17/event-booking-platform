# Phase 12 — Saga: distributed transactions, compensation, and failure handling

**Time:** 7–8 hours
**Needs:** Phase 11 finished (outbox working in both services).
**Pre-reading:** the CAP theorem in simple words; what "eventual consistency" means to a user.

This phase connects the earlier lessons. Booking confirmation moves from waiting HTTP writes to messages. Recovery actions are saved so they can survive a restart, instead of existing only inside a `catch` block.

---

## In plain English

This phase replaces the waiting seat-reservation call from Phase 6 with a booking process carried by messages. The first response says the booking is pending. A later message confirms or rejects it.

Example: booking-service saves a PENDING booking and a BookingCreated outbox message. Event-service reads the message and tries to reserve seats. Its reply lets booking-service change the status to CONFIRMED or REJECTED. If later work needs undoing, the system sends another saved message rather than relying only on a catch block.

Words used in this phase:

- **Saga:** a multi-step process using separate local transactions and recovery actions.
- **Local transaction:** changes committed together inside one service's database.
- **Compensation:** a new action that reverses the business effect of earlier work, such as releasing seats.
- **Rollback:** cancelling changes that have not committed in a transaction.
- **Eventual consistency:** services may briefly show different stages of the work, then agree after messages are handled.
- **Choreography:** services react to each other's events without one central coordinator.
- **Orchestration:** one coordinator tracks the process and asks services to perform each step.
- **Terminal state:** a final status that does not move to another status.
- **Timeout recovery:** finding work that has stayed pending too long and deciding how to recover it safely.

Start with the PENDING → CONFIRMED or REJECTED flow. Then follow the failure and cancellation paths. The outbox and duplicate checks from earlier phases are required parts of this flow.

---

# PART A — Understand the ideas

## A1. Why `@Transactional` stops at the service boundary

```java
@Transactional
public void bookSeat() {
    eventClient.reserveSeats(1, 2);      // event_db, over HTTP
    bookingRepository.save(booking);     // booking_db
}
```

**`@Transactional` protects the local database work.** It does not make the Feign call part of the same transaction. If the local `save` fails, its changes roll back, but seats already reserved by event-service stay reserved.

There are two databases and two separate commits. In Phase 6, a failure between them left seats reserved without a booking. Error handling alone cannot close that gap because the process may stop before it runs.

Two-phase commit from Phase 11 needs participating systems and a coordinator. An ordinary Feign HTTP call is not part of that protocol. We use local transactions, saved messages, and recovery actions instead.

## A2. ACID, BASE, CAP

**ACID** describes transaction guarantees: **Atomic** means all changes succeed or roll back together; **Consistent** means database rules are preserved; **Isolated** means concurrent work follows the chosen isolation rules; **Durable** means committed changes are kept after a restart. Keep these local database guarantees.

**BASE** is a way to describe systems that keep working while some data is temporarily unsettled: **Basically Available**, **Soft state**, **Eventually consistent**. For this project, the useful idea is that a booking can be accepted as pending while another service decides the seats.

**CAP** describes a choice during a network split. If services cannot communicate, you cannot guarantee both a fully current shared view (**Consistency**) and a successful answer to every request (**Availability**). A **Partition** is the network break causing that choice.

- **CP choice:** reject or delay affected requests rather than answer using a view that may disagree.
- **AP choice:** keep answering where possible, accepting that different parts may temporarily disagree and need to be brought back into agreement.

**Networks can fail.** Decide which operations may continue during a split and which must wait. The choice depends on the data and business rule.

**Our booking flow accepts a pending request first and decides the seats later.** The response can be quick because it does not wait for the reservation to finish. Each service still uses database rules to protect its own changes. The example timings are illustrations, not guaranteed response times.

## A3. Eventual consistency, from the user's side

```
t+0ms    POST /bookings → 202 Accepted, status PENDING
t+50ms   BookingCreated published
t+80ms   event-service reserves seats
t+120ms  SeatsReserved published
t+150ms  booking CONFIRMED
```

**For a short time, the booking exists but the seats are not reserved yet.** The user must see that it is pending. This affects the product behaviour, not just the internal code.

**Design for it rather than hiding it:**

- Return **202 Accepted**, not 201. The request was accepted; the outcome is not yet decided. HTTP
  already has the right status code for this.
- Give the client a way to see the outcome: poll `GET /bookings/{ref}`, or use WebSocket/SSE.
- Make the UI honest: "Confirming your booking…" and then a result.

**Show progress honestly.** Users already see pending payments and messages still sending. Say "Confirming your booking…" while work is pending, then show the real result. Do not report success before the decision arrives.

## A4. What a saga is

**A saga splits one workflow into local steps.** Each step commits in one service. If later work fails, a separate compensation action may be needed to reverse earlier business effects.

```
FORWARD                              COMPENSATE (on failure)
1. create booking (PENDING)   ──▶    mark booking REJECTED
2. reserve seats              ──▶    release seats
3. confirm booking            ──▶    (terminal, nothing to undo)
```

A completed local step stays committed. There is no one rollback covering the whole workflow. If needed, later steps perform separate undo actions, such as releasing seats.

**"Compensate" is not "rollback", and the difference matters:**

- **Rollback:** discards changes before that local transaction commits.
- **Compensation:** records a new action after earlier work committed, such as `CANCELLED`, `REFUNDED`, or `RELEASED`. The earlier action still happened and can remain in the history.

**Some actions cannot be fully undone.** An email cannot be unsent. A completed charge needs a refund rather than a local rollback. Where possible, put irreversible steps near the end of the workflow.

## A5. Choreography or orchestration

**Choreography:** each service listens for relevant events and reacts. No central component tells every service what to do next.

```
booking ──BookingCreated──▶ event-service
                               reserves seats
event ──SeatsReserved──▶ booking-service
                            confirms
```

**Benefit:** each service can react independently, and new listeners can be added.
**Cost:** the complete workflow is spread across services. You must follow several handlers to understand it, and accidental message loops are harder to spot.

**Orchestration:** one coordinator records progress and asks services to carry out the steps.

```
        ┌─── BookingSaga (orchestrator) ───┐
        │  1. reserve seats  → event-service│
        │  2. take payment   → payment      │
        │  3. confirm        → booking      │
        └──────────────────────────────────┘
```

**Benefit:** the steps, progress, timeouts, and recovery rules are easier to see in one place.
**Cost:** the coordinator is another dependency and can become too large if it takes over other services' business rules.

**This project uses choreography for a small flow.** A rough guide is to consider a coordinator as steps and branches increase, often beyond three or four steps. Payment and notification steps would be reasons to reconsider the choice.

## A6. Our saga

```
┌──────────────────────────────────────────────────────────────────────────┐
│ HAPPY PATH                                                               │
└──────────────────────────────────────────────────────────────────────────┘

booking-service                    Kafka                    event-service
──────────────                     ─────                    ─────────────
POST /bookings
  tx: INSERT booking (PENDING)
      INSERT outbox row
  COMMIT
  → 202 Accepted
        │
  poller ──BookingCreated(key=eventId)──▶ booking.events
                                              │
                                              ▼
                                        tx: dedup insert
                                            Event.reserveSeats()
                                            INSERT outbox row
                                        COMMIT
                                              │
        ◀──SeatsReserved(key=eventId)── event.events
        │
  tx: dedup insert
      booking.confirm()
  COMMIT                                   → CONFIRMED

┌──────────────────────────────────────────────────────────────────────────┐
│ COMPENSATION PATH (sold out)                                             │
└──────────────────────────────────────────────────────────────────────────┘
                                        reserveSeats() throws
                                        tx: dedup insert
                                            INSERT outbox SeatsRejected
                                        COMMIT
        ◀──SeatsRejected────────────────
  tx: booking.reject()                    → REJECTED
```

**Each arrow needs reliable sending and safe repeat handling:** an outbox saves outgoing work, and a consumer checks message ids before applying a repeated change.

- Booking-service can die after the commit → the outbox row publishes on restart (Phase 11).
- Event-service can be down for an hour → the message waits in Kafka (Phase 10).
- Any message can arrive twice → `processed_messages` skips it (Phase 9).
- Messages for one event are ordered → keyed by `eventId` (Phase 10a).

**The earlier phases work together here.** Kafka stores messages, the outbox preserves messages waiting to be sent, duplicate checks prevent repeated database effects, and keys keep related records in partition order. The saga depends on all of these.

## A7. Stuck sagas

**A booking can remain `PENDING` too long.** A reply may be delayed, a consumer may have a bug, or a topic may be configured incorrectly. Plan how to find and recover these bookings.

**Set a time limit for pending work.** A scheduled job looks for old unfinished sagas and starts the chosen recovery action:

```
booking PENDING for > 5 minutes
  → mark REJECTED
  → publish BookingCancelled (release any seats that were reserved)
  → alert
```

**A timeout does not prove the other service did nothing.** A successful reply might arrive after recovery has started. The recovery design must account for that late message as well.

**Check state transitions on late replies.** A `REJECTED` booking must not become `CONFIRMED` just because an old `SeatsReserved` message arrives. The state checks from Phase 6 reject that change. Seat-side recovery still needs its own correct handling.

---

# PART B — Build it

## Step 1 — Booking creation becomes asynchronous

**File:** `.../application/BookingService.java` (booking-service)

```java
    @Transactional
    public Booking createPending(CreateBookingRequest request, String userId) {
        EventDto event = eventGateway.getEvent(request.eventId());   // read-only pre-check

        if (!event.isBookable()) {
            throw new EventNotBookableException(
                    "Event %d is not open for booking".formatted(event.id()));
        }

        Booking booking = new Booking(userId, event.id(), event.title(),
                request.quantity(), event.priceCents());

        Booking saved = bookingRepository.save(booking);

        outboxWriter.write("Booking", String.valueOf(event.id()), "BookingCreated",
                "booking.events",
                Map.of("bookingId", saved.getId(),
                       "bookingRef", saved.getBookingRef(),
                       "eventId", event.id(),
                       "userId", userId,
                       "quantity", request.quantity()));

        log.info("Booking {} created as PENDING", saved.getBookingRef());
        return saved;
    }
```

**Compare this with Phase 6's version. Three things changed and all three matter:**

**The waiting `reserveSeats` write is removed.** Booking-service can accept pending work without waiting for event-service to reserve seats. Other dependencies, including the optional details read below, can still affect request handling.

**The booking stays `PENDING`.** The constructor already sets it; we no longer call `confirm()`. The
saga decides.

**The insert and the outbox row commit together** — one transaction, one database (Phase 11).

**`getEvent` is an early, read-only check.** It can give a quick error for an obviously invalid request. Event-service still makes the real reservation decision when it handles the saga message. Keeping the read improves feedback but still adds a waiting dependency to the current request.

**Use the event id for `aggregateId`, not the booking id.** This supplies the Kafka key. Messages about seats for one event must stay together, even when they involve different bookings.

**The controller returns 202:**

```java
    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request,
            Authentication authentication) {

        Booking booking = bookingService.createPendingIdempotent(
                request, authentication.getName(), idempotencyKey);

        return ResponseEntity.accepted()
                .header("Location", "/api/v1/bookings/" + booking.getBookingRef())
                .body(BookingResponse.from(booking));
    }
```

**Return `202 Accepted` and a `Location` header.** This says the request was accepted but confirmation is still pending. The client can call that URL to check until the booking leaves `PENDING`.

Keep the Phase 9 idempotency wrapper around it — the double-click problem has not gone away.

## Step 2 — Event-service consumes `BookingCreated`

**File:** `.../messaging/BookingEventsConsumer.java` (event-service)

```java
package com.eventbooking.event.messaging;

import com.eventbooking.event.application.SeatReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BookingEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingEventsConsumer.class);

    private final SeatReservationService reservationService;

    public BookingEventsConsumer(SeatReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @KafkaListener(topics = "booking.events", groupId = "event-service")
    public void onBookingEvent(EventEnvelope envelope) {
        MDC.put("correlationId", envelope.correlationId() == null
                ? envelope.messageId() : envelope.correlationId());
        try {
            switch (envelope.eventType()) {
                case "BookingCreated"   -> reservationService.handleBookingCreated(envelope);
                case "BookingCancelled" -> reservationService.handleBookingCancelled(envelope);
                default -> log.debug("Ignoring {}", envelope.eventType());
            }
        } finally {
            MDC.clear();
        }
    }
}
```

**File:** `.../application/SeatReservationService.java` (event-service)

```java
    @Transactional
    public void handleBookingCreated(EventEnvelope envelope) {
        if (deduplicator.alreadyProcessed(envelope.messageId(), CONSUMER)) {
            log.info("Duplicate BookingCreated {} - skipping", envelope.messageId());
            return;
        }

        Long eventId = envelope.payloadLong("eventId");
        Long bookingId = envelope.payloadLong("bookingId");
        String bookingRef = envelope.payloadString("bookingRef");
        int quantity = envelope.payloadInt("quantity");

        try {
            Event event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new EventNotFoundException(eventId));

            event.reserveSeats(quantity);

            outboxWriter.write("Event", String.valueOf(eventId), "SeatsReserved",
                    "event.events",
                    Map.of("bookingId", bookingId,
                           "bookingRef", bookingRef,
                           "eventId", eventId,
                           "quantity", quantity));

            log.info("Reserved {} seats on event {} for booking {} - {} remaining",
                    quantity, eventId, bookingRef, event.getAvailableSeats());

        } catch (InsufficientSeatsException | InvalidEventStateException | EventNotFoundException ex) {

            outboxWriter.write("Event", String.valueOf(eventId), "SeatsRejected",
                    "event.events",
                    Map.of("bookingId", bookingId,
                           "bookingRef", bookingRef,
                           "eventId", eventId,
                           "quantity", quantity,
                           "reason", ex.getMessage()));

            log.warn("Rejected booking {} on event {}: {}", bookingRef, eventId, ex.getMessage());
        }
    }
```

**This method is the heart of the saga. Five things are load-bearing:**

**Save all three changes in one transaction:** the processed-message id, seat change, and outbox reply. Either all commit or none do. If processing fails, do not advance the Kafka offset as though it succeeded. A later delivery can then retry the work safely.

**Not enough seats is a business outcome.** The handler writes `SeatsRejected` and commits that reply. Throwing the same business rejection repeatedly would retry a request that still cannot succeed and leave booking-service without its expected answer.

**Distinguishing "the dependency is broken" from "the answer is no" is the skill.** A sold-out event is
a valid answer; a database outage is not. Only the first should produce a reply.

**Reuse `Event.reserveSeats()` from Phase 4.** It still checks event status, enough seats, and valid counts. Those rules work for both HTTP and Kafka callers because they belong to the Event object.

**`@Version` still detects competing row changes.** Different event keys can be processed in parallel, while messages for one event stay in one partition. The version check provides extra protection when another write path also changes that row.

**Do not use `@RetryableTopic` for this seat flow.** It moves retries to another topic and lets later records overtake them. Use blocking retry through the container error handler when preserving the order matters more than continuing past a failed record.

## Step 3 — Booking-service consumes the reply

**File:** `.../application/BookingSagaService.java` (booking-service)

```java
    @Transactional
    public void handleSeatsReserved(EventEnvelope envelope) {
        if (deduplicator.alreadyProcessed(envelope.messageId(), CONSUMER)) {
            return;
        }

        String bookingRef = envelope.payloadString("bookingRef");

        bookingRepository.findByBookingRef(bookingRef).ifPresentOrElse(booking -> {
            try {
                booking.confirm();
                log.info("Booking {} CONFIRMED", bookingRef);
            } catch (InvalidBookingStateException ex) {
                log.warn("Late SeatsReserved for booking {} in state {} - compensating",
                        bookingRef, booking.getStatus());

                outboxWriter.write("Booking", envelope.payloadString("eventId"),
                        "BookingCancelled", "booking.events",
                        Map.of("bookingRef", bookingRef,
                               "eventId", envelope.payloadLong("eventId"),
                               "quantity", envelope.payloadInt("quantity"),
                               "reason", "LATE_CONFIRMATION"));
            }
        }, () -> log.error("SeatsReserved for unknown booking {}", bookingRef));
    }

    @Transactional
    public void handleSeatsRejected(EventEnvelope envelope) {
        if (deduplicator.alreadyProcessed(envelope.messageId(), CONSUMER)) {
            return;
        }

        String bookingRef = envelope.payloadString("bookingRef");

        bookingRepository.findByBookingRef(bookingRef).ifPresent(booking -> {
            booking.reject();
            log.info("Booking {} REJECTED: {}", bookingRef, envelope.payloadString("reason"));
        });
    }
```

**The `catch (InvalidBookingStateException)` block is the most interesting code in this phase, and it is
concept A7 in action.**

Scenario: the timeout job gave up on a booking and marked it `REJECTED`. Then `SeatsReserved` arrives
late. The seats *are* reserved, but the booking is already dead.

`booking.confirm()` throws, because `REJECTED → CONFIRMED` is not an allowed transition (Phase 6). **The
state machine caught a race against time**, and instead of corrupting the booking we publish
`BookingCancelled` so the seats are released.

**Compensation is a new action that reverses an earlier effect.** It is not a rollback of a transaction already committed in another service. The state methods and messages let that recovery be recorded and handled separately.

**Log an unknown booking as an error in `ifPresentOrElse`.** A reply without a matching booking can indicate the wrong topic, environment, or reference. It needs investigation rather than being quietly skipped.

## Step 4 — Cancellation, both directions

**File:** `.../application/BookingService.java`

```java
    @Transactional
    public Booking cancel(String bookingRef, String userId) {
        Booking booking = getForUser(bookingRef, userId);
        booking.cancel(userId);

        outboxWriter.write("Booking", String.valueOf(booking.getEventId()),
                "BookingCancelled", "booking.events",
                Map.of("bookingRef", bookingRef,
                       "eventId", booking.getEventId(),
                       "quantity", booking.getQuantity(),
                       "reason", "USER_REQUESTED"));

        return booking;
    }
```

**File:** `.../application/SeatReservationService.java` (event-service)

```java
    @Transactional
    public void handleBookingCancelled(EventEnvelope envelope) {
        if (deduplicator.alreadyProcessed(envelope.messageId(), CONSUMER)) {
            return;
        }

        Long eventId = envelope.payloadLong("eventId");
        int quantity = envelope.payloadInt("quantity");

        eventRepository.findById(eventId).ifPresent(event -> {
            event.releaseSeats(quantity);
            log.info("Released {} seats on event {} - {} available",
                    quantity, eventId, event.getAvailableSeats());
        });
    }
```

**Compare with Phase 6's cancel.** There, the release was a Feign call inside a `try/catch` that
swallowed failures and left seats stranded whenever event-service was down.

**The cancellation and its outbox row now commit together.** If event-service is unavailable, the saved message can be sent and handled after it returns. This is what **durable compensation** means: the recovery action is saved so a restart does not erase it.

**`releaseSeats` clamps at capacity** (Phase 4, step 6). That decision — made for tidiness back then —
is what makes a duplicate release harmless now. Idempotent thinking in the domain pays off later, in a
context you did not anticipate.

## Step 5 — The timeout job

**File:** `.../application/SagaTimeoutJob.java` (booking-service)

```java
package com.eventbooking.booking.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class SagaTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutJob.class);
    private static final Duration SAGA_TIMEOUT = Duration.ofMinutes(5);

    private final BookingRepository bookingRepository;
    private final OutboxWriter outboxWriter;

    public SagaTimeoutJob(BookingRepository bookingRepository, OutboxWriter outboxWriter) {
        this.bookingRepository = bookingRepository;
        this.outboxWriter = outboxWriter;
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void failStuckBookings() {
        Instant cutoff = Instant.now().minus(SAGA_TIMEOUT);

        List<Booking> stuck = bookingRepository
                .findByStatusAndCreatedAtBefore(BookingStatus.PENDING, cutoff);

        for (Booking booking : stuck) {
            booking.reject();

            outboxWriter.write("Booking", String.valueOf(booking.getEventId()),
                    "BookingCancelled", "booking.events",
                    Map.of("bookingRef", booking.getBookingRef(),
                           "eventId", booking.getEventId(),
                           "quantity", booking.getQuantity(),
                           "reason", "SAGA_TIMEOUT"));

            log.error("SAGA TIMEOUT: booking {} was PENDING for over {} minutes",
                    booking.getBookingRef(), SAGA_TIMEOUT.toMinutes());
        }
    }
}
```

**Concept A7.** Without this, a lost reply means a booking is `PENDING` forever — the user cannot use it
and cannot get rid of it, and the seats may or may not be held.

**`log.error`, not `warn`.** A saga timeout means the system failed to complete work it accepted. **This
should page someone.** Repeated timeouts mean a consumer is broken or a topic is misrouted, and the
sooner a human looks, the fewer users are affected.

**`BookingCancelled` is published defensively**, even though the seats may never have been reserved.
Releasing seats that were not reserved is safe because `releaseSeats` clamps at capacity — and being
safe in both directions is exactly why that clamp is there.

**Five minutes is a deliberate choice.** Long enough to survive a consumer restart or a Kafka blip;
short enough that a user is not left waiting. Too short and you compensate sagas that were about to
succeed (the late-reply race from step 3).

## Step 6 — Run the whole saga

```bash
REF=$(curl -s -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"eventId":1,"quantity":2}' | jq -r .bookingRef)

echo "booking $REF"

for i in 1 2 3 4 5; do
  curl -s http://localhost:8080/api/v1/bookings/$REF -H "Authorization: Bearer $USER_TOKEN" \
    | jq -r '.status'
  sleep 0.3
done
```

```
booking BK-3C9A17F2
PENDING
PENDING
CONFIRMED
CONFIRMED
CONFIRMED
```

**You can watch eventual consistency happen.** The booking existed as `PENDING`, then became
`CONFIRMED`, roughly 300–600 ms later.

Follow it through the logs with the correlation id:

```
[7f3a91c2] booking-service  Booking BK-3C9A17F2 created as PENDING
[7f3a91c2] booking-service  Published BookingCreated to partition 1 offset 12
[7f3a91c2] event-service    Reserved 2 seats on event 1 for booking BK-3C9A17F2 - 96 remaining
[7f3a91c2] event-service    Published SeatsReserved to partition 1 offset 8
[7f3a91c2] booking-service  Booking BK-3C9A17F2 CONFIRMED
```

**One id, two services, six messages, four database transactions, no distributed transaction anywhere.**
Every step is a local commit plus a durable message.

**Now the rejection path.** Set an event to 1 seat and book 5:

```bash
docker exec -it ebp-postgres psql -U ebp -d event_db \
  -c "UPDATE events SET available_seats = 1 WHERE id = 1;"

REF=$(curl -s -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" -d '{"eventId":1,"quantity":5}' | jq -r .bookingRef)

sleep 2
curl -s http://localhost:8080/api/v1/bookings/$REF -H "Authorization: Bearer $USER_TOKEN" | jq '.status'
```

```
"REJECTED"
```

**No exception reached the user. No seats were lost. No message went to a DLT.** A rejection is a normal
business outcome that travelled as an event.

---

## Break it on purpose

### Drill 1 — event-service down during a booking

Stop event-service and create a booking.

**`202 Accepted`, status `PENDING`.** The user's request succeeded. The `BookingCreated` message is
sitting in Kafka.

Start event-service. Within seconds the booking becomes `CONFIRMED`.

**Compare with Phase 6**, where this returned a 500 and the user simply could not book. **The write path
no longer requires both services to be alive at the same instant** — Phase 10a's decoupling, delivering
its main promise.

### Drill 2 — kill booking-service mid-saga

Create a booking and `kill -9` booking-service within a few hundred milliseconds.

Restart it. The outbox poller publishes the pending `BookingCreated`, event-service reserves the seats,
the reply arrives, and the booking is confirmed.

**A `kill -9` in the middle of a distributed transaction, and nothing was lost or double-counted.**
Phase 11's outbox and Phase 9's dedup did that together. Phase 6's `catch`-block compensation would have
leaked the seats.

### Drill 3 — duplicate messages

Republish a `BookingCreated` by hand with the **same** `messageId`:

```bash
docker exec -it ebp-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic booking.events \
  --property "parse.key=true" --property "key.separator=|"
```

```
1|{"messageId":"<copy an existing one>","eventType":"BookingCreated","version":1,"payload":{"bookingId":5,"bookingRef":"BK-3C9A17F2","eventId":1,"quantity":2}}
```

Log: *Duplicate BookingCreated … skipping*. **Seats unchanged.**

Now change the `messageId` and resend: seats are reserved **again**, for the same booking.

**The `messageId` is the only thing standing between you and double-reserving.** Phase 9's table doing
exactly the job it was built for.

### Drill 4 — a stuck saga

Stop event-service, create a booking, and leave it. After five minutes the timeout job fires:

```
ERROR SAGA TIMEOUT: booking BK-... was PENDING for over 5 minutes
```

Status is `REJECTED`, and a `BookingCancelled` was queued.

**Now the interesting part.** Start event-service. It processes the old `BookingCreated`, reserves the
seats, and publishes `SeatsReserved`. Booking-service receives it for a booking that is already
`REJECTED`:

```
WARN Late SeatsReserved for booking BK-... in state REJECTED - compensating
```

`BookingCancelled` is published, the seats are released, and the counts are correct again.

**Read that sequence twice.** The system raced against its own timeout, detected it through the state
machine, and repaired itself — **with no human involved and no data corrupted.** That is what a
well-built saga looks like, and it is only possible because the aggregate refuses illegal transitions.

### Drill 5 — out-of-order messages

Publish `BookingCancelled` **before** `BookingCreated` for the same booking, using the same key:

The cancellation releases seats that were never reserved — clamped at capacity, so harmless. The
creation then reserves them. **Counts end up correct, by design.**

Now do it with **different keys**, so they land on different partitions and are processed at the same time.
Run it several times. **You can produce a wrong seat count**, because two threads interleave without
ordering.

**The bug is in the key, not the code** — Phase 10a, concept A3, reproduced inside a real saga.

### Drill 6 — Kafka down for the whole flow

```bash
docker compose stop kafka
```

Create three bookings: all return `202 PENDING`. Outbox rows queue in booking-service.

```bash
docker compose start kafka
sleep 20
```

All three become `CONFIRMED`. **Kafka was unavailable for the entire request path and not one booking
was lost** — the outbox held them, exactly as in Phase 11.

### Drill 7 — the whole chain, broken deliberately

Comment out the dedup check in `handleBookingCreated`, then replay the topic:

```bash
docker exec -it ebp-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group event-service \
  --topic booking.events --reset-offsets --to-earliest --execute
```

**Every historical booking reserves seats again.** `available_seats` collapses, possibly to zero, for
events that are barely booked.

Restore the dedup, reset the offsets again, and watch every message be skipped. **Replay — Kafka's best
feature — is only safe with idempotent consumers.** This drill is the clearest possible demonstration of
why Phase 9 came before Phase 10.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Business rejection thrown instead of published | The saga hangs; message hits the DLT | Publish a rejection event, commit normally |
| Dedup insert in a different transaction | Silent loss on failure | One transaction |
| No saga timeout | Bookings `PENDING` forever | Timeout job + compensation |
| No state machine | Late replies corrupt terminal bookings | Reject illegal transitions |
| `@RetryableTopic` on ordered operations | Reservations processed after cancellations | Blocking retry where order matters |
| Wrong partition key | Happening at the same time processing of related messages | Key by the contested aggregate |
| Returning 201 for an async flow | Clients believe it is confirmed | 202 + `Location` |
| Compensation only in a `catch` block | Nothing runs when the process dies | Compensation as a durable message |
| Irreversible step early in the saga | Cannot compensate | Order irreversible steps last |
| No correlation id in events | Undebuggable across services | Carry it in the envelope |
| Choreography for a 7-step flow | Nobody can explain the flow | Coordinate beyond 3–4 steps |
| Assuming replies arrive in order | Rare, timing-dependent corruption | Idempotency + state machine |

---

## Notes for a real running system

- **Track longer sagas in a state table** with an id, current step, and times. This makes unfinished work easy to find.
- **Measure outcomes:** confirmed, rejected, and timed-out bookings per minute. Changes in those rates can reveal failures.
- **Alert on timeouts** because accepted work has not reached its expected outcome in time.
- **Provide an admin repair tool** for inspecting stuck work and starting controlled recovery.
- **Put irreversible actions near the end.** For example, secure seats before taking payment when that matches the business flow.
- **Consider orchestration** when the workflow has many steps or branches.
- **Set timeouts using measured completion times.** Leave room above p99, the time within which 99% of measured flows finish, so healthy but slower work is not timed out too soon.
- **Check for data differences regularly.** Compare confirmed booking quantities with `capacity - available_seats` and investigate mismatches.
- **Test compensation paths in Phase 13.** Recovery often runs during failures, so verify it before an incident occurs.

---

## Phase 12 checklist

- [ ] A booking returns `202 PENDING` and becomes `CONFIRMED` within a second
- [ ] Sold out produces `REJECTED` with no exception surfacing to the user
- [ ] Event-service down → the booking still succeeds and confirms later
- [ ] `kill -9` mid-saga loses nothing
- [ ] Duplicate messages change no seat counts
- [ ] A stuck saga times out, and a late reply compensates itself
- [ ] Kafka down for the whole flow → everything completes afterwards
- [ ] Replay without dedup destroyed your seat counts — you saw it
- [ ] One correlation id traces the whole saga across both services
- [ ] You can draw the saga, both paths, from memory
- [ ] You can explain compensation vs rollback
- [ ] You can say when you would switch to orchestration

---

## What Phase 13 does next

The system is correct. **You have proved that by hand, with curl and `kill -9`** — and every one of
those proofs disappears the moment you change a line of code.

Next: **testing.** Unit tests with Mockito, slice tests, MockMvc, and Testcontainers running real
Postgres, Redis and Kafka. The failure drills from every phase become tests that run on every build.

**Pre-reading for Phase 13:** the test pyramid, and why Testcontainers beats an in-memory H2 database.
