# Phase 10b — Kafka in Spring: producers, consumers, retries, DLT

**Time:** 4–5 hours
**Needs:** Phase 10a (concepts + topics created).

We start with genuinely one-way events — `EventCreated`, `EventUpdated`, `EventCancelled` — so the
plumbing is learned before Phase 12 makes the booking flow depend on it.

---

# PART A — Concepts

## A1. The event envelope

Every message shares a wrapper. Without one, each consumer invents its own parsing and there is no
consistent place to put a message id.

```json
{
  "messageId": "8f2c1e44-...",
  "eventType": "EventCancelled",
  "version": 1,
  "occurredAt": "2026-08-25T10:15:30Z",
  "correlationId": "7f3a91c2-...",
  "payload": { "eventId": 42, "reason": "VENUE_CLOSED" }
}
```

**Each field earns its place:**

- **`messageId`** — unique per message. **Phase 9's `processed_messages` deduplicates on it.** Without
  it there is no way to build an idempotent consumer. Generate it at publish time, once — never
  regenerate it on a retry, or the dedup is worthless.
- **`eventType`** — lets one topic carry several types (Phase 10a, concept A8) and lets a consumer skip
  what it does not care about.
- **`version`** — schema version. When you must break the shape, `version: 2` messages can be routed to
  different handling while `version: 1` messages still in the log continue to work.
- **`occurredAt`** — when the fact happened, not when it was consumed. Consumers may lag by hours.
- **`correlationId`** — Phase 3's id, now crossing the async boundary. Without it, the trace dies at the
  producer and you cannot connect a user's request to what happened five seconds later.

## A2. Serialisation, and the trusted-packages trap

Spring's `JsonSerializer` adds a header `__TypeId__` containing the **fully qualified Java class name**,
and `JsonDeserializer` uses it to pick a class.

**Convenient, and wrong across services**, for two reasons:

1. It hard-couples the consumer to the producer's package names. Move a class and every consumer breaks.
2. Deserialising into a class named by an attacker-controlled header is a **deserialisation
   vulnerability**. `spring.json.trusted.packages` exists to limit it, and `*` defeats the point.

**Our approach:** turn type headers **off** on the producer, and have the consumer deserialise into
**its own** envelope class, dispatching on the `eventType` **field** rather than a Java class name.

```
producer: JsonSerializer, addTypeInfo = false
consumer: JsonDeserializer bound to ITS OWN envelope class
          switch on envelope.eventType()
```

**Message contracts are data, not Java classes.** A consumer written in Go must be able to read your
events; a Java class name in a header is meaningless to it. This is the same "copy the shape, never
share the class" rule from Phase 6, applied to messages.

## A3. Acknowledgement in Spring Kafka

`AckMode` decides when the offset is committed:

| Mode | Commits |
|---|---|
| `RECORD` | after each record's listener returns |
| `BATCH` (default) | after the whole poll batch is processed |
| `MANUAL` | when you call `ack.acknowledge()`, at the end of the batch |
| `MANUAL_IMMEDIATE` | immediately when you call `acknowledge()` |

**Never `enable.auto.commit=true`** — Phase 10a, concept A5: it commits on a timer unrelated to your
work, giving you both loss and duplicates depending on timing.

**We use `RECORD`.** Each message is committed after its listener returns normally; a thrown exception
means no commit, so it is redelivered. That is at-least-once, which is what we want, and
Phase 9's dedup handles the duplicates.

**The subtlety worth knowing:** the listener returning is *not* the same as your database transaction
committing. If the offset commit succeeds and the transaction later fails, you lose the work. Doing the
database write and the dedup insert **in one transaction inside the listener**, before returning, is what
makes this correct.

## A4. Error handling: what should retry and what should not

Two kinds of failure, and treating them alike is the mistake:

- **Transient** — database down, network blip. Retry; it will succeed later.
- **Permanent** — malformed JSON, an unknown event id, a business rule violated. Retrying forever
  achieves nothing and blocks the partition.

**`@RetryableTopic` implements non-blocking retry** (Phase 10a, concept A7):

```
event.events ──fail──▶ event.events-retry-0 ──fail──▶ event.events-retry-1 ──▶ event.events-dlt
```

The failed message is republished to a retry topic and the offset is committed, so **the main partition
keeps moving**. Other messages are not held hostage by one bad one.

**The cost: ordering is lost for the retried message.** It will be processed after messages that came
after it. For our seat operations that matters, which is why Phase 12 revisits this choice — sometimes
blocking retry is the correct trade, precisely because it preserves order.

`exclude` on `@RetryableTopic` sends permanent failures straight to the DLT with no retries. Always
list your validation and not-found exceptions there.

---

# PART B — Build it

## Step 1 — Dependency and shared config

Add to **event-service** and **booking-service**:

```xml
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
```

Version comes from the Spring Boot BOM.

**File:** `application.yml` (both services)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:29092}

    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        linger.ms: 10
        spring.json.add.type.headers: false

    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      properties:
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: com.eventbooking.*
        max.poll.records: 10
        max.poll.interval.ms: 300000

    listener:
      ack-mode: RECORD
      concurrency: 3
```

**`localhost:29092`, not `9092`.** Phase 1's two-listener setup: our services run on the host, so they
use the host listener. Using `9092` gives a connection that appears to work and then times out — the
classic Docker/Kafka confusion.

**`acks: all` + `enable.idempotence: true`** — Phase 10a, concept A4. Idempotence requires
`acks=all`, `retries > 0` and `max.in.flight ≤ 5`; modern clients enforce it. Correct in dev even though
our single broker cannot demonstrate it.

**`spring.json.add.type.headers: false`** — concept A2. No Java class names in your message contract.

**`ErrorHandlingDeserializer` wrapping `JsonDeserializer`** — this one is important. Without it, a
malformed message throws **inside the consumer's poll loop**, before any error handler runs, and the
consumer **retries the same bad message forever**. A single unparseable message stops the partition
permanently. The wrapper catches it and hands a null to the error handler, so it can go to the DLT.

**`auto-offset-reset: earliest`** — a brand-new consumer group reads from the beginning. `latest` would
silently skip everything published before it started, which during a deploy means quietly dropped
messages.

**`concurrency: 3`** — three consumer threads, matching the three partitions. More would idle
(Phase 10a).

**`max.poll.records: 10`** — small batches, so processing stays well inside `max.poll.interval.ms` and
rebalance storms are avoided.

## Step 2 — The envelope

**File:** `.../messaging/EventEnvelope.java` (in **both** services — copy, do not share)

```java
package com.eventbooking.event.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String messageId,
        String eventType,
        int version,
        Instant occurredAt,
        String correlationId,
        Map<String, Object> payload
) {
    public static EventEnvelope of(String eventType, String correlationId,
                                   Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                1,
                Instant.now(),
                correlationId,
                payload);
    }

    public long payloadLong(String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public int payloadInt(String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    public String payloadString(String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
```

**`Map<String, Object>` for the payload rather than a typed class** — deliberate, and it is the
forward-compatibility decision. A typed payload means every field the producer adds requires a consumer
change to deserialise safely. A map accepts anything and the consumer reads only what it needs.

The typed accessors keep the ugliness in one place. Jackson deserialises JSON numbers to `Integer` or
`Long` depending on magnitude, so `(Long) payload.get("eventId")` throws a `ClassCastException` for
small values — a genuinely nasty intermittent bug that these helpers prevent.

**`@JsonIgnoreProperties(ignoreUnknown = true)`** so producers can add envelope fields without breaking
consumers. Same rule as Phase 6.

## Step 3 — The publisher

**File:** `.../messaging/EventPublisher.java` (event-service)

```java
package com.eventbooking.event.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    public static final String TOPIC = "event.events";

    private final KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    public EventPublisher(KafkaTemplate<String, EventEnvelope> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String eventType, Long eventId, Map<String, Object> payload) {
        EventEnvelope envelope = EventEnvelope.of(
                eventType, MDC.get("correlationId"), payload);

        kafkaTemplate.send(TOPIC, String.valueOf(eventId), envelope)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error("FAILED to publish {} for event {} messageId={}",
                                eventType, eventId, envelope.messageId(), failure);
                    } else {
                        log.info("Published {} for event {} to partition {} offset {}",
                                eventType, eventId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
```

**`String.valueOf(eventId)` as the key is the load-bearing line.** Phase 10a, concept A3: same key →
same partition → ordered. Every message about event 42 is ordered relative to every other message about
event 42, while different events process in parallel.

**`MDC.get("correlationId")`** picks up Phase 3's id from the current request thread and carries it into
the message. The trace now survives the async hop.

**`whenComplete` is a callback, not a wait.** `send()` is asynchronous and returns a
`CompletableFuture`. Calling `.get()` on it would block a request thread for the round trip — the exact
thing async messaging exists to avoid.

**And here is the problem this design still has, stated plainly:** if the publish fails, all we do is
log. The database change already committed. **The event is lost and nothing will retry it.**

That is the **dual-write problem**, and it is what Phase 11's outbox exists to solve. Notice that no
amount of error handling in this method fixes it — the failure can be the process dying between the
commit and the send.

## Step 4 — Publish from the domain

**File:** `.../application/EventService.java`

```java
    @Transactional
    public Event create(CreateEventRequest request, String createdBy) {
        Event event = new Event(/* ... as before ... */);
        Event saved = eventRepository.save(event);

        eventPublisher.publish("EventCreated", saved.getId(), Map.of(
                "eventId", saved.getId(),
                "title", saved.getTitle(),
                "startsAt", saved.getStartsAt().toString(),
                "capacity", saved.getCapacity(),
                "priceCents", saved.getPriceCents(),
                "status", saved.getStatus().name()));

        return saved;
    }

    @Transactional
    public Event cancel(Long id) {
        Event event = getById(id);
        event.cancel();

        eventPublisher.publish("EventCancelled", id, Map.of(
                "eventId", id,
                "title", event.getTitle(),
                "bookedSeats", event.bookedSeats()));

        return event;
    }
```

**Fat events** (Phase 10a, concept A8): the payload carries what a consumer needs, so nobody has to call
back to event-service and recreate the synchronous coupling.

**`bookedSeats` on the cancellation** is a good example of thinking about the consumer: booking-service
will want to know how many bookings are affected, and by the time it consumes, that number may have
changed. **The event carries the fact as it was.**

**Note the publish sits inside `@Transactional`.** That is worse than it looks, and worth naming now:
the send may go out and *then* the transaction rolls back. Consumers would react to an event that never
happened. Phase 11 fixes this properly.

## Step 5 — The consumer

**File:** `.../messaging/EventEventsConsumer.java` (booking-service)

```java
package com.eventbooking.booking.messaging;

import com.eventbooking.booking.application.BookingCancellationService;
import com.eventbooking.booking.application.MessageDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class EventEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventEventsConsumer.class);
    private static final String CONSUMER = "booking-service.event-events";

    private final MessageDeduplicator deduplicator;
    private final BookingCancellationService cancellationService;

    public EventEventsConsumer(MessageDeduplicator deduplicator,
                               BookingCancellationService cancellationService) {
        this.deduplicator = deduplicator;
        this.cancellationService = cancellationService;
    }

    @KafkaListener(
            topics = "event.events",
            groupId = "booking-service",
            containerFactory = "kafkaListenerContainerFactory")
    public void onEventMessage(@Payload EventEnvelope envelope,
                               @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                               @Header(KafkaHeaders.OFFSET) long offset) {

        MDC.put("correlationId", envelope.correlationId() == null
                ? envelope.messageId() : envelope.correlationId());

        try {
            log.info("Received {} messageId={} partition={} offset={}",
                    envelope.eventType(), envelope.messageId(), partition, offset);

            switch (envelope.eventType()) {
                case "EventCancelled" -> handleEventCancelled(envelope);
                case "EventCreated", "EventUpdated" -> log.debug("Ignoring {}", envelope.eventType());
                default -> log.warn("Unknown event type {} - ignoring", envelope.eventType());
            }
        } finally {
            MDC.clear();
        }
    }
```

**`groupId = "booking-service"`** — Phase 10a, concept A3. Every instance of booking-service shares this
group, so each message is handled once across the whole service. A second service consuming the same
topic uses its own group and gets its own copy.

**`MDC.put(...)` restores the correlation id**, so log lines in this consumer join the trace that started
in the user's HTTP request minutes ago. **`MDC.clear()` in a `finally`** — Phase 3's thread-local rule.
Kafka listener threads are long-lived and reused for thousands of messages, so a leaked value would
poison every later log line.

**`default -> log.warn` and continue, not throw.** An unknown event type means the producer added
something new; that is not an error for us. Throwing would send perfectly valid messages to the DLT.
**Ignore what you do not understand; fail only on what you cannot handle.**

**Now the handler, with the dedup that makes it correct:**

```java
    private void handleEventCancelled(EventEnvelope envelope) {
        if (deduplicator.alreadyProcessed(envelope.messageId(), CONSUMER)) {
            log.info("Skipping duplicate messageId={}", envelope.messageId());
            return;
        }

        Long eventId = envelope.payloadLong("eventId");
        int cancelled = cancellationService.cancelAllBookingsForEvent(
                eventId, envelope.messageId());

        log.info("Cancelled {} bookings for cancelled event {}", cancelled, eventId);
    }
}
```

**File:** `.../application/BookingCancellationService.java`

```java
    @Transactional
    public int cancelAllBookingsForEvent(Long eventId, String messageId) {
        List<Booking> affected = bookingRepository
                .findByEventIdAndStatusIn(eventId,
                        List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED));

        affected.forEach(booking -> booking.cancelBySystem("EVENT_CANCELLED"));
        return affected.size();
    }
```

**The single most important detail in this phase:** `alreadyProcessed` inserts into
`processed_messages`, and **that insert must commit in the same transaction as the booking
cancellations**.

```
one transaction:
    INSERT INTO processed_messages (message_id, ...)
    UPDATE bookings SET status = 'CANCELLED' ...
    COMMIT
```

If they were separate:

```
mark processed  ✓ committed
cancel bookings ✗ failed
→ redelivery is skipped as a duplicate. The bookings are never cancelled. Silent loss.
```

Phase 9, step 8 said this; here is where it bites. **Restructure so the dedup insert happens inside the
same `@Transactional` method as the work** — either move the dedup call into
`cancelAllBookingsForEvent`, or make the listener method itself transactional. As written above they
are two transactions, which is a bug **left visible on purpose**: find it, then fix it, and you will
never write it again.

Add `cancelBySystem` to the `Booking` aggregate — a cancellation with no owner check, because the system
is acting, not the user.

## Step 6 — Retries and the DLT

**File:** `.../messaging/EventEventsConsumer.java`

```java
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10000),
            dltTopicSuffix = ".DLT",
            autoCreateTopics = "false",
            exclude = {
                    IllegalArgumentException.class,
                    BookingNotFoundException.class
            })
    @KafkaListener(topics = "event.events", groupId = "booking-service")
    public void onEventMessage(...) { ... }
```

**And the DLT handler**, which is not optional:

```java
    @DltHandler
    public void handleDlt(@Payload EventEnvelope envelope,
                          @Header(KafkaHeaders.ORIGINAL_TOPIC) String originalTopic,
                          @Header(KafkaHeaders.EXCEPTION_MESSAGE) String errorMessage) {

        log.error("DLT: {} messageId={} from {} failed permanently: {}",
                envelope.eventType(), envelope.messageId(), originalTopic, errorMessage);
        // production: persist for inspection, raise an alert, expose a replay endpoint
    }
```

**`exclude` is the line that stops pointless work.** A malformed message will fail identically on every
attempt; four tries and three sleeps achieve nothing except delay. Permanent failures go straight to the
DLT. **Classifying your exceptions is the actual work here** — the annotation is trivial.

**`autoCreateTopics = "false"`** because we created the DLTs deliberately in Phase 10a. Auto-creation
hides typos.

**Backoff: 1 s → 2 s → 4 s.** Long enough for a database restart, short enough that a transient blip
recovers quickly.

**Understand what `@RetryableTopic` actually does** (Phase 10a, concept A7): the failed message is
**republished** to a retry topic and the original offset is **committed**. The main partition keeps
moving — and **the retried message is now out of order relative to its siblings.** For `EventCancelled`
that is acceptable. For seat reservations in Phase 12 it is not, and we will choose differently there.

## Step 7 — Run it

Rebuild both services and restart everything.

```bash
# terminal 1: watch the topic
docker exec -it ebp-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic event.events \
  --property print.key=true --property print.partition=true

# terminal 2: create an event
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"Jazz Night","venue":"Blue Note","startsAt":"2026-12-05T20:00:00Z","capacity":50,"priceCents":300000}' \
  | jq -r '.id'
```

The console consumer prints the envelope:

```
7  {"messageId":"8f2c...","eventType":"EventCreated","version":1,
    "occurredAt":"2026-08-25T10:15:30Z","correlationId":"7f3a91c2-...",
    "payload":{"eventId":7,"title":"Jazz Night",...}}
```

**The key is `7` — the event id.** Publish an update for the same event and it lands in the same
partition, at the next offset.

**Now the full flow:** publish the event, book a seat on it, then cancel the event:

```bash
curl -s -X POST http://localhost:8080/api/v1/events/7/publish -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null
curl -s -X POST http://localhost:8080/api/v1/bookings -H "Authorization: Bearer $USER_TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" -H "Content-Type: application/json" \
  -d '{"eventId":7,"quantity":1}' | jq -r .bookingRef

curl -s -X POST http://localhost:8080/api/v1/events/7/cancel -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null
sleep 2
curl -s http://localhost:8080/api/v1/bookings -H "Authorization: Bearer $USER_TOKEN" | jq '.content[0].status'
```

```
"CANCELLED"
```

**Nobody called booking-service.** Event-service published a fact; booking-service reacted. The two
services have no compile-time or runtime dependency on each other in this direction — event-service does
not know booking-service exists.

**Grep the correlation id across all three logs.** One id, one HTTP request, one Kafka message, two
services, spread over seconds. That is distributed tracing working across an async boundary.

### The message lifecycle

```
1.  EventService.cancel()  → domain change, transaction commits
2.  EventPublisher.publish() → envelope built, messageId generated
3.  KafkaTemplate.send(topic, key="7", envelope)
4.  Producer serialises, batches (linger.ms=10), picks partition = murmur2("7") % 3
5.  Broker appends to the partition log; acks=all; offset assigned
6.  whenComplete callback logs partition + offset
--- later, in another process ---
7.  Consumer poll() returns the record
8.  ErrorHandlingDeserializer → JsonDeserializer → EventEnvelope
9.  @KafkaListener invoked; MDC restored from correlationId
10. Dedup check on messageId in processed_messages
11. Business work + dedup insert, one transaction, commit
12. Listener returns → ack-mode RECORD → offset committed
--- on failure ---
13. Exception → republish to event.events-retry-0 (1 s), then -retry-1 (2 s) …
14. Attempts exhausted → event.events.DLT → @DltHandler → alert
```

---

## Break it on purpose

### Drill 1 — the consumer is down

Stop booking-service. Cancel an event. Nothing consumes it.

```bash
docker exec -it ebp-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group booking-service
```

`LAG` is non-zero. Now start booking-service: it consumes the backlog and the bookings are cancelled.

**Nothing was lost.** Compare with Phase 6: a synchronous call to a dead service simply fails. **This is
the availability decoupling from Phase 10a, concept A1** — and it is the strongest single argument for
async communication.

### Drill 2 — duplicate delivery

Publish the same envelope twice by hand:

```bash
docker exec -it ebp-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic event.events \
  --property "parse.key=true" --property "key.separator=|"
```

```
7|{"messageId":"dup-test-1","eventType":"EventCancelled","version":1,"occurredAt":"2026-08-25T10:00:00Z","payload":{"eventId":7}}
7|{"messageId":"dup-test-1","eventType":"EventCancelled","version":1,"occurredAt":"2026-08-25T10:00:00Z","payload":{"eventId":7}}
```

Log shows the first processed, the second: *Skipping duplicate messageId=dup-test-1*.

**Now change the second `messageId` to `dup-test-2` and send again.** It is processed as a new message.
**The `messageId` is the identity** — which is why it must be generated once, at publish, and never
regenerated on a retry.

### Drill 3 — a poison message

Send something the consumer cannot handle:

```
7|{"messageId":"bad-1","eventType":"EventCancelled","version":1,"payload":{"eventId":"not-a-number"}}
```

Watch the retry topics fill, then the DLT:

```bash
docker exec -it ebp-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic event.events.DLT --from-beginning
```

**Now the important observation: check the main topic's lag.** It is **zero**. The partition kept moving
while the bad message went off to be retried elsewhere. That is non-blocking retry earning its keep.

Then remove `ErrorHandlingDeserializer` from the config and send genuinely malformed JSON. The consumer
now **loops forever** on the same message, at full speed, filling your logs, and nothing behind it is
processed. **One bad message stopped a partition permanently** — the failure mode that config line
exists to prevent.

### Drill 4 — the transaction bug in step 5

With the dedup and the work in **separate** transactions, make the business work fail after the dedup
insert commits (throw at the end of `cancelAllBookingsForEvent`).

The message is retried, the dedup says "already processed", and it is skipped. **The bookings are never
cancelled and nothing reports a problem.**

Then fix it — one transaction — and repeat. The dedup insert rolls back with the work, the retry
processes it properly.

**This drill is the whole reason Phase 9 came before Phase 10.** It is also the most common bug in
production Kafka consumers.

### Drill 5 — ordering, and losing it

Publish two messages for the same event with **different keys**:

```
7|{"messageId":"m1","eventType":"EventUpdated",...}
99|{"messageId":"m2","eventType":"EventCancelled","payload":{"eventId":7}}
```

Both concern event 7, but they are on different partitions and processed by different threads. **The
cancellation may be handled before the update.**

**The bug is entirely in the key**, not in any code. Phase 10a, concept A3, reproduced deliberately.

### Drill 6 — the dual-write problem, previewed

Make `KafkaTemplate.send` fail — stop Kafka — and cancel an event:

```bash
docker compose stop kafka
curl -s -X POST http://localhost:8080/api/v1/events/7/cancel -H "Authorization: Bearer $ADMIN_TOKEN"
```

**The HTTP call returns 200.** The event is cancelled in the database. The log shows
`FAILED to publish`. Booking-service never hears about it, so **bookings for a cancelled event stay
CONFIRMED, forever.**

Start Kafka again — nothing is republished. That message never existed.

**No `try/catch` fixes this**, because the process could equally have died between the commit and the
send. **This is Phase 11.**

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| `bootstrap-servers: localhost:9092` from the host | Connects, then times out | `29092` — the host listener |
| No `ErrorHandlingDeserializer` | One bad message loops forever | Wrap the JSON deserializer |
| Type headers enabled across services | Consumer coupled to producer packages; unsafe | `add.type.headers: false` |
| `trusted.packages: "*"` | Deserialisation vulnerability | Name your packages |
| `enable-auto-commit: true` | Loss *and* duplicates | Manual / `RECORD` ack |
| `auto-offset-reset: latest` on a new group | Silently skips existing messages | `earliest` |
| Dedup insert in a separate transaction | Silent data loss on failure | One transaction |
| No `messageId` | Cannot deduplicate at all | Always include it |
| Regenerating `messageId` on retry | Dedup never matches | Generate once, at publish |
| Throwing on unknown event types | Valid messages sent to the DLT | Log and ignore |
| No `exclude` on `@RetryableTopic` | Malformed messages retried pointlessly | List permanent failures |
| No `@DltHandler` | Failures are invisible | Log and alert |
| MDC not cleared in the listener | Wrong correlation ids for thousands of messages | `finally { MDC.clear(); }` |
| `concurrency` > partition count | Idle threads | Match the partitions |
| Blocking on `send().get()` | Request threads wait for the broker | Use the callback |

---

## Production considerations

- **Alert on consumer lag and on DLT depth.** These two graphs tell you almost everything.
- **A DLT replay tool** — an admin endpoint that republishes a DLT message to the main topic after the
  bug is fixed. Manual `kafka-console-producer` at 3am is not a strategy.
- **Schema registry** (Avro/Protobuf) if the message contract matters across teams.
- **Idempotent consumers everywhere**, without exception.
- **Never publish personal data** you may have to delete; retention and compaction make deletion hard.
- **Watch rebalance frequency**; tune `max.poll.records` down if processing is slow.
- **Consumer concurrency ≤ partition count.**
- **Separate the transaction boundary from the listener** so the database work and the dedup commit
  together and the offset commits after.
- **Metrics per event type**, not just per topic — you want to know *which* event is failing.

---

## Phase 10b checklist

- [ ] Creating an event publishes `EventCreated` with the event id as the key
- [ ] Cancelling an event cancels its bookings, with no HTTP call between the services
- [ ] One correlation id spans the HTTP request and the Kafka message
- [ ] A stopped consumer builds lag and catches up with no loss
- [ ] A duplicate `messageId` is skipped
- [ ] A poison message reaches the DLT and the main partition keeps moving
- [ ] Removing `ErrorHandlingDeserializer` caused an infinite loop — you saw it
- [ ] You reproduced the separate-transaction bug and fixed it
- [ ] Wrong keys produced out-of-order processing
- [ ] **Kafka down = the event is lost forever, and the API still returned 200**
- [ ] You can explain why `send()` succeeding does not mean the message is stored

---

## What Phase 11 does next

Drill 6 is the unfinished business, and it is the same shape as Phase 6's leaked seats: **two writes,
two systems, no atomicity.** The database commits and the message is lost, or the message is sent and
the database rolls back.

Next: the **Transactional Outbox.** Write the event to your own database, in the same transaction as the
business change, and publish it afterwards from a poller. One transaction, guaranteed delivery,
at-least-once — which Phase 9 already made safe.

**Pre-reading for Phase 11:** the dual-write problem — why a database commit and a message send cannot
be atomic.
