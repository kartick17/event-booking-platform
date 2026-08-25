# Phase 12 — Saga: distributed transactions, compensation, and failure handling

**Time:** 7–8 hours
**Needs:** Phase 11 finished (outbox working in both services).
**Pre-reading:** the CAP theorem in plain terms; what "eventual consistency" means to a user.

This is the phase the whole project has been building towards. The synchronous booking flow from
Phase 6 gets replaced by an event-driven saga, and the hand-rolled `catch`-block compensation becomes a
durable message.

---

# PART A — Concepts

## A1. Why `@Transactional` stops at the service boundary

```java
@Transactional
public void bookSeat() {
    eventClient.reserveSeats(1, 2);      // event_db, over HTTP
    bookingRepository.save(booking);     // booking_db
}
```

**The annotation covers one thing: one connection to one database.** The Feign call is not part of it.
If `save` fails, the transaction rolls back — and the seats stay reserved, because nothing about that
HTTP call is transactional.

Two databases, two independent commits, no coordinator. Phase 6's Drill 3 proved it: seats leaked
permanently and no `try/catch` could stop it, because the process can die between the two operations.

**And 2PC is not the answer** (Phase 11, concept A2): no HTTP API supports it, it blocks, and its
coordinator is a single point of failure.

## A2. ACID, BASE, CAP

**ACID** describes one database: Atomic, Consistent, Isolated, Durable. Within one service, keep it.

**BASE** describes a distributed system: **B**asically **A**vailable, **S**oft state, **E**ventually
consistent. Not a lesser goal — a different one, forced by physics.

**CAP:** during a network **P**artition you may keep **C**onsistency or **A**vailability, not both.

- **CP** — refuse writes until the partition heals. Correct, unavailable. (Banking ledgers.)
- **AP** — keep accepting writes, reconcile later. Available, temporarily inconsistent. (Almost
  everything else, including Eureka — Phase 1.)

**Partitions are not optional.** Networks fail; the only choice is what you do when they do.

**Our choice is AP**: accept the booking immediately, resolve the seats moments later. The user gets a
response in 50 ms instead of waiting on two services.

## A3. Eventual consistency, from the user's side

```
t+0ms    POST /bookings → 202 Accepted, status PENDING
t+50ms   BookingCreated published
t+80ms   event-service reserves seats
t+120ms  SeatsReserved published
t+150ms  booking CONFIRMED
```

**For 150 milliseconds the booking exists but the seats are not yet reserved.** That is the window, and
it is a *product* decision, not just a technical one.

**Design for it rather than hiding it:**

- Return **202 Accepted**, not 201. The request was accepted; the outcome is not yet decided. HTTP
  already has the right status code for this.
- Give the client a way to see the outcome: poll `GET /bookings/{ref}`, or use WebSocket/SSE.
- Make the UI honest: "Confirming your booking…" and then a result.

**The honest framing:** you already live with this. Card payments are "pending" for days. A posted
message shows a spinner. Users tolerate "in progress" perfectly well; what they hate is being told
something succeeded when it did not.

## A4. What a saga is

**A sequence of local transactions, each with a compensating transaction that semantically undoes it.**

```
FORWARD                              COMPENSATE (on failure)
1. create booking (PENDING)   ──▶    mark booking REJECTED
2. reserve seats              ──▶    release seats
3. confirm booking            ──▶    (terminal, nothing to undo)
```

Each step commits locally. **There is no global rollback** — instead, a later step failing triggers
undo actions that are themselves local transactions.

**"Compensate" is not "rollback", and the difference matters:**

- Rollback erases history: the row never existed.
- Compensation is a **new fact**: `CANCELLED`, `REFUNDED`, `RELEASED`. The original still happened and
  is still visible.

**Some things cannot be compensated.** You cannot un-send an email; you send an apology. You cannot
un-charge instantly; you refund. **Order your saga so irreversible steps come last** — this is the
single most useful design rule for sagas.

## A5. Choreography or orchestration

**Choreography** — each service listens for events and reacts. No central brain.

```
booking ──BookingCreated──▶ event-service
                               reserves seats
event ──SeatsReserved──▶ booking-service
                            confirms
```

**Good:** no single point of failure, services stay decoupled, easy to add a listener.
**Bad:** the flow exists nowhere as a whole. To understand it you read every service. Cycles are easy to
create by accident, and hard to debug.

**Orchestration** — one coordinator tells each service what to do and tracks progress.

```
        ┌─── BookingSaga (orchestrator) ───┐
        │  1. reserve seats  → event-service│
        │  2. take payment   → payment      │
        │  3. confirm        → booking      │
        └──────────────────────────────────┘
```

**Good:** the flow is in one readable place, state is explicit, compensation logic is centralised,
timeouts are easy.
**Bad:** the orchestrator becomes a dependency for everyone, and can drift into a god service.

**Rule of thumb: choreography up to 3–4 steps, orchestration beyond that.** Our flow is two steps, so
**choreography**. In an interview, say you would move to orchestration when payment and notification
steps arrive — that shows judgement rather than dogma.

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

**Every arrow is an outbox row plus an idempotent consumer.** Nothing depends on a process staying
alive:

- Booking-service can die after the commit → the outbox row publishes on restart (Phase 11).
- Event-service can be down for an hour → the message waits in Kafka (Phase 10).
- Any message can arrive twice → `processed_messages` skips it (Phase 9).
- Messages for one event are ordered → keyed by `eventId` (Phase 10a).

**Read that list again.** Phases 9, 10, 11 were not separate topics; they were the four preconditions
that make this diagram trustworthy. **A saga built without them is a saga that corrupts data.**

## A7. Stuck sagas

The failure mode nobody plans for: a booking sits `PENDING` forever because a reply never came — the
message was lost before the outbox existed, a consumer had a bug, a topic was misconfigured.

**Every saga needs a timeout.** A scheduled job finds sagas older than N minutes still in a
non-terminal state and compensates them:

```
booking PENDING for > 5 minutes
  → mark REJECTED
  → publish BookingCancelled (release any seats that were reserved)
  → alert
```

**Compensating a timed-out saga is itself risky**, and this is the subtle part: the reply may arrive
*after* you compensate. Now seats are released and a `SeatsReserved` message says they are held.

**That is why the state machine and idempotency matter.** `REJECTED → CONFIRMED` is not an allowed
transition (Phase 6, step 5), so the late reply is rejected by the aggregate rather than corrupting it.
**The state machine is your last line of defence against time itself.**

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

**No `reserveSeats` call.** No synchronous write to another service. Booking-service's availability no
longer multiplies with event-service's for the write path (Phase 6, concept A1).

**The booking stays `PENDING`.** The constructor already sets it; we no longer call `confirm()`. The
saga decides.

**The insert and the outbox row commit together** — one transaction, one database (Phase 11).

**The `getEvent` call is a read-only convenience.** It gives a fast, friendly error for an obviously
wrong request. **It is not part of the saga's correctness** — the real decision is made by
event-service's aggregate. If it fails, we could skip it and let the saga reject the booking instead;
keeping it is a UX choice, and worth recognising as one.

**`aggregateId` is the event id, not the booking id** — Phase 10a, concept A8. All messages about one
event must be ordered relative to each other. Keying by booking id would let two bookings for the same
event be processed out of order by different partitions.

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

**`202 Accepted` with a `Location` header is the honest, standard answer** for "we took your request,
the outcome is not decided yet, look here for the result." The client polls that URL until the status
leaves `PENDING`.

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

**One transaction covers everything** — the dedup insert, the seat change, and the outbox row. All three
commit or none do. If this transaction fails, the offset is not committed, the message is redelivered,
and it is retried cleanly. **This single property is what makes the saga safe**, and it is exactly what
Phase 10b's Drill 4 taught by breaking it.

**A business rejection is not an exception here — it is an outcome.** The `catch` writes a
`SeatsRejected` event and the transaction **commits normally**. Letting the exception escape would
retry, fail identically, and eventually land in the DLT — while booking-service waits forever for a
reply that will never come.

**Distinguishing "the dependency is broken" from "the answer is no" is the skill.** A sold-out event is
a valid answer; a database outage is not. Only the first should produce a reply.

**`Event.reserveSeats()` is the Phase 4 aggregate method, unchanged.** All the invariants — status
check, sufficiency, no negative seats — are enforced here without a single line being added. **This is
the payoff for putting rules in the domain instead of in a controller.** The same method now serves HTTP
and Kafka.

**`@Version` from Phase 8 is still protecting us.** Three consumer threads may process bookings for
different events in parallel; two messages for the *same* event are in the same partition and therefore
serialised. Optimistic locking is the backstop for the case where they are not.

**No retry annotation here.** A `@RetryableTopic` would republish to a retry topic and **lose ordering**
(Phase 10b, concept A4) — a reservation could be processed after a cancellation. Blocking retry via the
container's error handler is the right trade for seat operations, because ordering matters more than
throughput. **The opposite choice from Phase 10b, for a good reason.**

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

**This is what "compensation" really looks like in a running system:** not a rollback, but a new fact
that restores balance. And notice it was made possible by a design decision taken six phases ago, for
different reasons.

**`ifPresentOrElse` with an error log for an unknown booking.** That should be impossible; if it
happens, something is deeply wrong (a wrong topic, a mixed-up environment) and you want it screaming in
the log rather than silently ignored.

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

Now: the cancellation and the outbox row commit together, and the message is delivered **whenever
event-service is available** — in a second or in an hour. **The compensation is durable.** That word is
the entire difference between Phase 6 and Phase 12.

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

Now do it with **different keys**, so they land on different partitions and are processed concurrently.
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
| Wrong partition key | Concurrent processing of related messages | Key by the contested aggregate |
| Returning 201 for an async flow | Clients believe it is confirmed | 202 + `Location` |
| Compensation only in a `catch` block | Nothing runs when the process dies | Compensation as a durable message |
| Irreversible step early in the saga | Cannot compensate | Order irreversible steps last |
| No correlation id in events | Undebuggable across services | Carry it in the envelope |
| Choreography for a 7-step flow | Nobody can explain the flow | Orchestrate beyond 3–4 steps |
| Assuming replies arrive in order | Rare, timing-dependent corruption | Idempotency + state machine |

---

## Production considerations

- **A saga state table** for anything longer than two steps: saga id, current step, timestamps. Without
  it, "which sagas are stuck?" is unanswerable.
- **Dashboards for saga outcomes** — confirmed / rejected / timed out per minute. A rising timeout rate
  is your earliest signal that something is broken.
- **Alert on saga timeouts.** Every one is work you accepted and did not complete.
- **An admin repair tool** — inspect a stuck saga and force compensation. `psql` at 3am is not a plan.
- **Order steps so irreversible actions are last.** Charge the card after the seats are secured, not
  before.
- **Move to orchestration** when steps exceed three or four, or when the flow branches.
- **Set the timeout from real data** — well above p99 completion time, or you will compensate sagas that
  were about to succeed.
- **Reconciliation:** periodically compare `sum(confirmed bookings)` against `capacity -
  available_seats`. Distributed systems drift; find it before a customer does.
- **Test compensation paths explicitly** (Phase 13). The happy path gets exercised constantly; the
  compensation path runs only during incidents, which is the worst time to discover it is broken.

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
