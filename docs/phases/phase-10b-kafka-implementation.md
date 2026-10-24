# Phase 10b — Kafka in Spring: producers, consumers, retries, DLT

**Time:** 4–5 hours
**Needs:** Phase 10a (concepts + topics created).

We begin with notifications: `EventCreated`, `EventUpdated`, and `EventCancelled`. First learn to send and read messages. Phase 12 then uses them to decide booking outcomes.

---

## In plain English

This phase connects your Java services to Kafka. Event-service sends messages when events change, and booking-service reads messages and reacts.

Start with a simple notification: an admin cancels an event, event-service publishes EventCancelled, and booking-service handles it. This lets you learn sending, reading, errors, and duplicate checks before messages control seat confirmation in Phase 12.

Words used in this phase:

- **Envelope:** shared outer fields on every message, including its unique id, type, version, and time.
- **Payload:** the data describing what happened.
- **Serializer / deserializer:** code converting a Java object to message data / converting message data back to an object.
- **Listener:** a method called when a message arrives.
- **Acknowledgement:** recording that a message has been handled so the group can move forward.
- **Retry topic:** a separate stream holding messages to try again later.
- **DLT (Dead Letter Topic):** a separate stream for work that still failed after the allowed attempts.
- **Deduplication:** using saved message ids to avoid applying a repeated message twice.

Start by looking at one message's fields. Then follow it from the producer to the listener and finally to the database change.

---

# PART A — Understand the ideas

## A1. The event envelope

Every message uses the same outer structure, called an **envelope**. It gives consumers a consistent place to find the message id, type, version, and other shared information.

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

- **`messageId`:** a unique id saved in Phase 9's `processed_messages` table. Generate it once when creating the message, then keep it for retries so duplicate checks work.
- **`eventType`:** says what happened and lets consumers choose which messages to handle.
- **`version`:** identifies the message format so incompatible formats can be handled separately.
- **`occurredAt`:** records when the event happened, rather than when the consumer read it.
- **`correlationId`:** connects this message to the logs for the original request.
- **`payload`:** contains the details needed to handle the event.

## A2. Serialisation, and the trusted-packages trap

By default, Spring's `JsonSerializer` can send the full Java class name in a `__TypeId__` header. `JsonDeserializer` can use that header to choose the class it creates. This is convenient inside a shared Java setup, but needs care across services.

**Convenient, and wrong across services**, for two reasons:

1. A consumer becomes dependent on the producer's package and class names. Moving a class can break message reading.
2. Reading a class name from an untrusted header can be unsafe. `spring.json.trusted.packages` limits allowed packages; using `*` removes that restriction.

**We turn off producer type headers.** Each consumer reads messages into its own envelope class and uses the `eventType` field to decide what to do. The contract is the JSON data, rather than a Java class name.

```
producer: JsonSerializer, addTypeInfo = false
consumer: JsonDeserializer bound to ITS OWN envelope class
          switch on envelope.eventType()
```

**Agree on message data, not Java classes.** Another service, including one written in Go, should be able to read the message without knowing the producer's package names. This is the same separation used for DTOs in Phase 6.

## A3. Acknowledgement in Spring Kafka

`AckMode` decides when the offset is committed:

| Mode | Commits |
|---|---|
| `RECORD` | after each record's listener returns |
| `BATCH` (default) | after the whole poll batch is processed |
| `MANUAL` | when you call `ack.acknowledge()`, at the end of the batch |
| `MANUAL_IMMEDIATE` | immediately when you call `acknowledge()` |

**Do not enable `enable.auto.commit=true` for this flow.** It can save the reading position on a timer rather than after the work finishes. That can lose work or cause repeats after failures.

**We use `RECORD`.** Spring saves the offset after a listener successfully handles the record. Failed processing goes through the error handler and retry policy. Because delivery can repeat, the database work still needs duplicate protection.

**Finish the database transaction before the listener returns.** Save the business change and processed-message id together. If the offset is saved first but the database later fails, Kafka may move on even though the work was not saved.

## A4. Error handling: what should retry and what should not

Two kinds of failure, and treating them alike is the mistake:

- **Temporary failure:** for example, a database or network is briefly unavailable. A later attempt may succeed.
- **Permanent failure:** for example, the message cannot be parsed or violates a rule that will not change by waiting. Repeating it forever will not help.

**`@RetryableTopic` implements non-blocking retry** (Phase 10a, concept A7):

```
event.events ──fail──▶ event.events-retry-0 ──fail──▶ event.events-retry-1 ──▶ event.events-dlt
```

A retry topic holds the failed message while the main partition continues. Other records do not have to wait for that retry.

**The cost is changed processing order.** The retried record may finish after later records. For seat operations, order matters, so Phase 12 considers when waiting on the same partition is the safer choice.

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

**Use `localhost:29092`, not `9092`.** The applications run on your computer rather than inside Docker, so they need Kafka's host listener from Phase 1. A wrong advertised address can connect initially and then fail on later requests.

**`acks: all` + `enable.idempotence: true`** — Phase 10a, concept A4. Idempotence requires
`acks=all`, `retries > 0` and `max.in.flight ≤ 5`; modern clients enforce it. Correct in dev even though
our single broker cannot demonstrate it.

**`spring.json.add.type.headers: false`** — concept A2. No Java class names in your message contract.

**`ErrorHandlingDeserializer` catches errors while reading message data.** Without it, invalid JSON can fail before the listener and its normal error handling are reached. The wrapper lets Spring pass the failure to the configured recovery path instead of leaving the consumer stuck on unreadable data.

**`auto-offset-reset: earliest`** — a brand-new consumer group reads from the beginning. `latest` would
without a clear error skip everything published before it started, which during a deploy means quietly dropped
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
small values — a really nasty intermittent bug that these helpers prevent.

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

**`String.valueOf(eventId)` chooses the message key.** With the same partition setup, records for one event go to the same partition and have a defined order there. Different events can be handled on different partitions at the same time.

**`MDC.get("correlationId")`** picks up Phase 3's id from the current request thread and carries it into
the message. The trace now survives the async hop.

**`whenComplete` runs code when the send finishes.** It does not wait in the request thread. `send()` returns a `CompletableFuture`; calling `.get()` there would hold the request thread while waiting for Kafka's answer.

**And here is the problem this design still has, stated plainly:** if the publish fails, all we do is
log. The database change already committed. **The event is lost and nothing will retry it.**

**This is the dual-write problem:** the database can commit before the message is safely sent. A process crash between those operations loses the notification. Logging errors cannot recover missing work without a saved record; Phase 11 adds that record.

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

**The cancellation message includes `bookedSeats` as it was when the event happened.** The count could change before a consumer reads it. Including the value lets the consumer understand that past event without looking up today's value.

**Sending inside `@Transactional` does not join Kafka to the database transaction.** The message may be sent and then the database may roll back. Consumers would hear about a change that did not commit. The outbox in Phase 11 addresses this.

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

**All instances of booking-service use `groupId = "booking-service"`.** They share partition work rather than each independently reading every record. Delivery can still repeat after failures. An independent service uses a different group to read the same topic for its own job.

**`MDC.put(...)` puts the original correlation id back into the consumer's log context.** This connects message handling to the earlier HTTP request. Always call `MDC.clear()` in `finally`, because the listener thread will be reused for other messages.

**For an unknown event type, use `default -> log.warn` and continue.** It may be a new type this consumer does not need. Do not treat it as a broken message just because this service has no handler for it.

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

**Save the duplicate-check record and business change in the same `@Transactional` method.** Move the check into `cancelAllBookingsForEvent`, or make the listener method transactional. As written, the example uses two transactions. That is a known learning exercise: if one commits and the other fails, a retry can be skipped even though the business work did not finish.

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

**`exclude` lists failures that should not be retried.** Repeating an unreadable message or a permanently invalid action only delays other work. Send it to the DLT through the configured recovery path. Decide which exceptions are temporary and which need repair.

**`autoCreateTopics = "false"`** because we created the DLTs deliberately in Phase 10a. Auto-creation
hides typos.

**Backoff: 1 s → 2 s → 4 s.** Long enough for a database restart, short enough that a temporary blip
recovers quickly.

**`@RetryableTopic` sends the failed message to another topic, then saves the original offset.** The main topic continues, so the retry can finish after later messages. This is acceptable for the notification example here. Phase 12 chooses differently for seat operations that need their processing order preserved.

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

Then remove `ErrorHandlingDeserializer` from the config and send really incorrectly formatted JSON. The consumer
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
| `auto-offset-reset: latest` on a new group | Without a clear error skips existing messages | `earliest` |
| Dedup insert in a separate transaction | Silent data loss on failure | One transaction |
| No `messageId` | Cannot deduplicate at all | Always include it |
| Regenerating `messageId` on retry | Dedup never matches | Generate once, at publish |
| Throwing on unknown event types | Valid messages sent to the DLT | Log and ignore |
| No `exclude` on `@RetryableTopic` | Incorrectly formatted messages retried pointlessly | List permanent failures |
| No `@DltHandler` | Failures are invisible | Log and alert |
| MDC not cleared in the listener | Wrong correlation ids for thousands of messages | `finally { MDC.clear(); }` |
| `concurrency` > partition count | Idle threads | Match the partitions |
| Blocking on `send().get()` | Request threads wait for the broker | Use the callback |

---

## Notes for a real running system

- **Alert on lag and DLT size.** These show delayed work and work that failed.
- **Provide a reviewed way to replay DLT messages** after fixing the cause, rather than relying on ad hoc terminal commands during a failure.
- **Consider a schema registry** when several teams share message formats.
- **Protect every consumer against duplicates**, using a saved message id with its database changes.
- **Avoid personal data you may need to delete later.** Stored message histories and replay make deletion more complicated.
- **Watch repeated rebalances.** If processing is slow, a smaller `max.poll.records` can reduce work per poll.
- **Keep active consumers within the useful partition count.** Extra consumers in a group do not add partition capacity.
- **Commit database work and the duplicate record together**, before the listener returns and the offset is saved.
- **Measure failures by event type**, so you can tell which kind of work is failing inside a topic.

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
