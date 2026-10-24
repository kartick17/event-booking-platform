# Phase 11 — The Transactional Outbox

**Time:** 5–6 hours
**Needs:** Phase 10b finished.
**Pre-reading:** the dual-write problem — why a database commit and a message send cannot be atomic.

Phase 10b showed that an event cancellation could be saved while its message was lost. Booking-service then never learned about it. This phase saves the message with the database change so it can still be sent after a restart.

---

## In plain English

This phase stops a saved business change from losing its message when the application crashes. It saves the message to an outbox table before a background job sends it to Kafka.

Example: cancelling an event must also tell booking-service. Save both the cancellation and a message-to-send in one local transaction. Either both are saved or neither is. If Kafka is down, the saved message waits in the outbox and can be sent later.

Words used in this phase:

- **Dual write:** updating two separate systems, such as Postgres and Kafka, for one action.
- **Atomic:** changes succeed together or are rolled back together within the protected operation.
- **Outbox:** a table in the service's own database holding messages that need sending.
- **Publisher / relay:** the background work that sends saved outbox messages to Kafka.
- **Polling:** checking regularly for work to do.
- **CDC (Change Data Capture):** reading database changes from its log instead of repeatedly querying a table.
- **Backlog:** work saved but not yet completed.
- **Partial index:** an index containing only rows matching a condition, such as unpublished messages.
- **At-least-once:** a message can be sent again after an uncertain result. Consumers must handle duplicates.

Start with A1: compare a crash after the database save with a crash before the Kafka send. Then see why storing the message alongside the change closes that gap.

---

# PART A — Understand the ideas

## A1. The dual-write problem

**One user action sometimes needs two separate writes.** For example, save an event change in Postgres and send a notification to Kafka. Without a shared transaction, one can succeed while the other fails.

```java
@Transactional
public void cancel(Long id) {
    Event event = getById(id);
    event.cancel();                          // write 1: database
    publisher.publish("EventCancelled", ...); // write 2: Kafka
}
```

**Four ways this breaks, and none is exotic:**

```
1. DB commits, Kafka send fails
   → the event happened, nobody was told.  SILENT INCONSISTENCY.

2. Kafka send succeeds, DB rolls back
   → consumers react to something that never happened.  PHANTOM EVENT.

3. Process dies between the commit and the send
   → same as case 1, and no catch block runs.

4. Kafka accepted it into the producer buffer; process dies before the flush
   → send() "succeeded" from your code's point of view.  Message gone.
```

**A crash can happen between the two writes.** An ordinary `catch`, retry loop, or `finally` block cannot guarantee recovery after the process has been killed or the machine loses power. The missing work needs a saved record.

**The cause:** the database and Kafka save independently. One successful save does not guarantee the other happened.

## A2. Why not a distributed transaction

**Two-phase commit (XA)** uses a coordinator. It first asks all participants to prepare their changes, then tells them to commit. This needs support from every participating system.

We do not use it here because:

- **Kafka does not participate in XA transactions**, and ordinary HTTP APIs do not join them either.
- **Participants can hold locks while waiting**, reducing how much work they can complete.
- **A coordinator failure can leave work waiting for a decision** about whether to commit or roll back.
- **It adds coordination across systems.** This project instead saves local work first and lets services agree through messages.

**Our solution:** save the business change and the message together in one database, then send that saved message separately.

## A3. The outbox

**Add an outbox row in the same transaction as the business change.** Both records are in the service's database. If that transaction fails, neither is saved; if it commits, both are saved.

```
ONE TRANSACTION
   UPDATE events SET status = 'CANCELLED' WHERE id = 7
   INSERT INTO outbox_events (event_type, payload, ...) VALUES (...)
   COMMIT                                   ← both, or neither

SEPARATELY, later
   poller: SELECT ... FROM outbox_events WHERE published_at IS NULL
           → send to Kafka
           → UPDATE outbox_events SET published_at = NOW()
```

**Why this survives failures:**

- **The change and message-to-send commit together.** One cannot be committed without the other by this transaction.
- **A restart does not erase a committed outbox row.** The publisher can find it later.
- **If Kafka is down, messages wait in the table.** They can be sent when Kafka recovers.
- **A message may be sent twice** if sending succeeds but marking it as published fails. Consumers therefore still need duplicate checks.

**Expect at-least-once sending.** The publisher may repeat a send after an uncertain result. A consumer must save its processed-message id and business changes together to prevent repeated database effects.

> **How to explain it:** "Save the message and business change in one local database transaction. A background publisher sends the message later. Crashes can delay it, and retries can duplicate it, so consumers must recognise duplicates."

## A4. Two ways to get rows out of the table

### Polling publisher — what we build

A scheduled job looks for rows not yet published and sends their messages. This is called a **polling publisher**.

**Benefits:** simple application code, no extra platform, and easy local testing.
**Costs:** messages wait until the next check, the job regularly queries the database, and multiple application instances need to divide the work safely.

### Change Data Capture (CDC) — Debezium

**CDC** reads the database's own change log and sends those changes to Kafka. Debezium is a tool for this. Postgres uses a write-ahead log to record changes; a connector reads the relevant changes from it.

**Benefits:** fewer polling queries and quick delivery of changes.
**Costs:** more tools to run, including Debezium and Kafka Connect. You must also monitor replication slots: if the connector stops reading, retained database log data can fill the disk.

**We build polling because it is easier to learn and operate here.** Consider CDC when measured polling load or delay justifies the extra tools. Understand the benefits and costs of both approaches.

## A5. Ordering, and being honest about it

**Related messages need the right order.** Publish outbox rows in `id` order and use the aggregate id as the Kafka key. When several publishers run, the locking and batch strategy must also preserve the required order. Different aggregates do not need one global order.

**Watch a partial batch failure.** If rows 1 and 3 are sent but row 2 fails, retrying row 2 later puts it after row 3. Sorting the original batch alone does not prevent this.

**Two honest options:**

1. **Stop the batch at the first failure** for that aggregate. Simple; a stuck row blocks later ones for
   the same aggregate — which is usually what you want, since they are causally related.
2. **Accept it** and make consumers order-tolerant (checking a version or timestamp in the payload).

**We choose to stop later messages for the same aggregate after a failure.** For example, do not send `EventCancelled` ahead of an earlier failed `EventUpdated` for that event. Delay is preferable to the wrong order.

## A6. Multiple instances

Three booking-service instances all polling the same table:

```
instance A: SELECT unpublished → rows 1,2,3
instance B: SELECT unpublished → rows 1,2,3     ← the same rows
→ every event published three times
```

Repeated sends can be handled by duplicate checks, but we should avoid several publishers doing the same work unnecessarily. Use row locking to divide the work:

```sql
SELECT * FROM outbox_events
 WHERE published_at IS NULL
 ORDER BY id
 LIMIT 50
 FOR UPDATE SKIP LOCKED;
```

**`FOR UPDATE SKIP LOCKED` lets workers take different rows.** If A locks rows 1–50, B skips them and takes other unlocked rows. The database coordinates the workers, so they do not need a separate Redis lock or a chosen leader.

This SQL feature is available in Postgres, MySQL 8+, and Oracle. It is useful when several workers take jobs from a database table.

---

# PART B — Build it

## Step 1 — The table

**File:** `services/event-service/src/main/resources/db/migration/V2__create_outbox.sql`
(and the same in **booking-service** as its next version)

```sql
CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    message_id     VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(100),
    payload        TEXT         NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ,
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     TEXT,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_outbox_message_id UNIQUE (message_id)
);

CREATE INDEX idx_outbox_unpublished
    ON outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_published_at ON outbox_events (published_at);
```

**The partial index is the most important line in this file.**

```sql
WHERE published_at IS NULL
```

A **partial index** only contains rows matching that condition — so it holds only the unpublished
backlog, typically a handful of rows, not the millions you have published over the last year. The
polling query stays microsecond-fast forever, and the index barely uses memory.

**Without it, the poller gets slower every day** and eventually becomes the reason the service falls
over. A really excellent thing to mention in an interview.

**The other columns:**

- `aggregate_type` and `aggregate_id` identify the object the event concerns. `aggregate_id` supplies the Kafka key, which chooses the partition.
- `message_id` has a `UNIQUE` constraint. Generate it once when saving the outbox row, and keep it unchanged for every resend. The consumer records it in `processed_messages`.
- `payload TEXT` stores the complete envelope as it was at the time of the change. Rebuilding it later from the entity could accidentally describe newer data.
- `attempts`, `last_error`, and `next_attempt_at` record retry progress, the last failure, and when the row may be tried again.
- `topic` records the destination, allowing one service's outbox table to hold messages for several topics.

## Step 2 — Entity

**File:** `.../outbox/OutboxEvent.java`

```java
package com.eventbooking.event.outbox;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "message_id", nullable = false, unique = true, length = 100)
    private String messageId;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType,
                       String topic, String payload, String correlationId) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.messageId = UUID.randomUUID().toString();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error != null && error.length() > 2000
                ? error.substring(0, 2000) : error;
        long backoffSeconds = Math.min(300, (long) Math.pow(2, Math.min(attempts, 8)));
        this.nextAttemptAt = Instant.now().plus(Duration.ofSeconds(backoffSeconds));
    }

    public boolean isExhausted(int maxAttempts) {
        return attempts >= maxAttempts;
    }
}
```

(Add getters.)

**Create `messageId` when constructing the outbox row.** A retry must keep the same id, so the consumer can recognise a message it has already handled. Generating a fresh id every time it is sent would bypass the duplicate check.

**`markFailed` saves the retry delay in the row.** The delays increase from 2 seconds to 4, 8, and up to 300 seconds, or 5 minutes. Because `next_attempt_at` is in the database, a restart does not erase the waiting schedule. A failing row is not retried on every poll.

**Truncating `lastError` at 2000 characters** — a full stack trace can be tens of kilobytes, and a table
of them will surprise you.

## Step 3 — Repository

**File:** `.../outbox/OutboxRepository.java`

```java
package com.eventbooking.event.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select o from OutboxEvent o
             where o.publishedAt is null
               and o.nextAttemptAt <= :now
             order by o.id asc
            """)
    List<OutboxEvent> findBatchForPublishing(@Param("now") Instant now, Pageable pageable);

    long countByPublishedAtIsNull();

    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt is not null and o.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
```

**`lock.timeout = -2` is Hibernate's magic value for `SKIP LOCKED`.** Combined with
`PESSIMISTIC_WRITE`, Hibernate emits:

```sql
SELECT ... FOR UPDATE SKIP LOCKED
```

Concept A6 — every instance takes a different slice with no coordination.

**Verify the generated SQL.** Enable `logging.level.org.hibernate.SQL: DEBUG` and check for `skip locked`. Without the intended locking, multiple publishers may take the same work or block each other. Test with more than one instance before relying on the behaviour.

`order by o.id asc` gives oldest-first publishing, preserving insertion order (concept A5).

`countByPublishedAtIsNull()` is your **backlog metric** — the number to graph and alert on.

## Step 4 — Writing to the outbox

**File:** `.../outbox/OutboxWriter.java`

```java
package com.eventbooking.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventbooking.event.messaging.EventEnvelope;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void write(String aggregateType, String aggregateId, String eventType,
                      String topic, Map<String, Object> payload) {

        String correlationId = MDC.get("correlationId");

        OutboxEvent row = new OutboxEvent(
                aggregateType, aggregateId, eventType, topic, "", correlationId);

        EventEnvelope envelope = new EventEnvelope(
                row.getMessageId(), eventType, 1, row.getCreatedAt(), correlationId, payload);

        try {
            row.setPayload(objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise outbox payload", ex);
        }

        repository.save(row);
    }
}
```

**There is no `@Transactional` on this method, and that is the entire design.**

It **joins the caller's transaction**. `EventService.cancel()` is `@Transactional`, so the `UPDATE
events` and this `INSERT INTO outbox_events` commit together, or neither does.

**Do not add `@Transactional(REQUIRES_NEW)` here.** A separate outbox transaction could commit while the business transaction fails, or fail after the business transaction commits. Both records must use the same transaction for the outbox pattern to work.

The envelope is built with **the row's own `messageId`** so the id in the message and the id in the
table are the same value — which is what makes republishing safe.

## Step 5 — Switch the domain to the outbox

**File:** `.../application/EventService.java`

Replace every `eventPublisher.publish(...)` with `outboxWriter.write(...)`:

```java
    @Transactional
    public Event cancel(Long id) {
        Event event = getById(id);
        event.cancel();

        outboxWriter.write(
                "Event", String.valueOf(id), "EventCancelled",
                EventPublisher.TOPIC,
                Map.of("eventId", id,
                       "title", event.getTitle(),
                       "bookedSeats", event.bookedSeats()));

        return event;
    }
```

**The change is small and the guarantee is completely different.** Before: a network call inside a
transaction, lost if anything went wrong. Now: a local insert that commits atomically with the state
change.

**Also notice the transaction is now faster and safer** — no network call inside it (Phase 6's rule).
Before, a slow Kafka broker held a database connection open.

## Step 6 — The publisher

**File:** `.../outbox/OutboxPublisher.java`

```java
package com.eventbooking.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventbooking.event.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository repository;
    private final KafkaTemplate<String, EventEnvelope> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxRepository repository,
                           KafkaTemplate<String, EventEnvelope> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch = repository.findBatchForPublishing(
                Instant.now(), PageRequest.of(0, BATCH_SIZE));

        if (batch.isEmpty()) {
            return;
        }

        Set<String> blockedAggregates = new HashSet<>();

        for (OutboxEvent row : batch) {
            if (blockedAggregates.contains(row.getAggregateId())) {
                continue;                       // keep this aggregate's events in order
            }
            try {
                EventEnvelope envelope =
                        objectMapper.readValue(row.getPayload(), EventEnvelope.class);

                kafkaTemplate.send(row.getTopic(), row.getAggregateId(), envelope)
                        .get(5, TimeUnit.SECONDS);

                row.markPublished();

            } catch (Exception ex) {
                row.markFailed(ex.getMessage());
                blockedAggregates.add(row.getAggregateId());

                if (row.isExhausted(MAX_ATTEMPTS)) {
                    log.error("Outbox row {} ({} for {}) exhausted after {} attempts",
                            row.getId(), row.getEventType(), row.getAggregateId(),
                            row.getAttempts(), ex);
                } else {
                    log.warn("Outbox publish failed for row {}, attempt {}: {}",
                            row.getId(), row.getAttempts(), ex.getMessage());
                }
            }
        }
    }
}
```

**Five decisions in this method, each worth understanding:**

**`fixedDelay = 500` waits half a second after one run finishes before starting the next.** `fixedRate` schedules starts at regular intervals instead; whether work can overlap also depends on the scheduler setup. Polling adds delivery delay. Shorter delays reduce that wait but create more database queries.

**Wait for Kafka's result here before marking the row published.** `.get(5, TimeUnit.SECONDS)` limits that wait to 5 seconds. This happens on a background scheduler thread, not a user request thread. It still uses a worker and can hold a transaction or lock, so keep the timeout and batch size controlled.

**`blockedAggregates`** implements concept A5. If `EventUpdated` for event 7 fails, we skip every later
row for event 7 in this batch so ordering is preserved. Other aggregates continue unaffected.

**`markPublished` and `markFailed` need no `save()`** — the entities are managed inside `@Transactional`
and dirty checking writes them at commit (Phase 4, Drill 5).

**Failures do not abort the batch.** One bad row must not stop 49 good ones. Each row succeeds or fails
on its own.

**Used up rows keep being polled** with a 5-minute backoff. In production, move them to a
`outbox_events_failed` table or alert loudly — a row that has failed ten times needs a human, not a
retry.

Add `@EnableScheduling` to the main class.

## Step 7 — Cleanup and observability

```java
    @Scheduled(cron = "0 23 4 * * *")
    @Transactional
    public void deleteOldPublishedEvents() {
        int deleted = repository.deletePublishedBefore(Instant.now().minus(Duration.ofDays(7)));
        if (deleted > 0) {
            log.info("Outbox cleanup removed {} published rows", deleted);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void reportBacklog() {
        long backlog = repository.countByPublishedAtIsNull();
        if (backlog > 100) {
            log.warn("Outbox backlog is {} - publishing may be stuck", backlog);
        }
    }
}
```

**Keep published rows for seven days in this example.** They record what was sent and when, which helps investigate failures. The cleanup job later removes old published rows.

**Watch the outbox backlog:** rows waiting to be published. A growing backlog can mean Kafka is unavailable, the publisher stopped, or messages keep failing. Measure both the count and the age of the oldest row, and alert when work is stuck.

## Step 8 — Run it

Rebuild and restart. Cancel an event and watch the row's life:

```bash
curl -s -X POST http://localhost:8080/api/v1/events/7/cancel -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null

docker exec -it ebp-postgres psql -U ebp -d event_db -c \
  "SELECT id, event_type, aggregate_id, published_at, attempts FROM outbox_events ORDER BY id DESC LIMIT 3;"
```

Within half a second `published_at` is set. To catch it mid-flight, stop the app, cancel via SQL, and
start it again.

---

## Break it on purpose

### Drill 1 — Kafka down (the drill this phase exists for)

```bash
docker compose stop kafka

curl -s -X POST http://localhost:8080/api/v1/events/8/cancel -H "Authorization: Bearer $ADMIN_TOKEN" | jq .status

docker exec -it ebp-postgres psql -U ebp -d event_db -c \
  "SELECT id, event_type, published_at, attempts, next_attempt_at FROM outbox_events WHERE published_at IS NULL;"
```

The API returns 200, the event is cancelled, and the outbox row sits unpublished with `attempts`
climbing and `next_attempt_at` pushed further out each time.

**Now bring Kafka back:**

```bash
docker compose start kafka
sleep 15
docker exec -it ebp-postgres psql -U ebp -d event_db -c \
  "SELECT count(*) FROM outbox_events WHERE published_at IS NULL;"
```

Zero. **The event was delivered, minutes late, with nothing lost.**

**Compare this with Phase 10b Drill 6.** There, the cancellation message could disappear. Here, the committed outbox row remains available for a later send. The user-visible change may be delayed, but the work to notify booking-service is saved.

### Drill 2 — kill the process mid-flight

Stop the outbox publisher (comment out `@Scheduled`, or use a profile). Cancel three events — three rows
queue up. **Kill the application with `kill -9`.** No shutdown hook runs, no `finally` block executes.

Restart it. All three publish.

**`kill -9` is the case no error handling can survive**, and the outbox does not care, because the
durable record was committed by the database before the process died.

### Drill 3 — the `REQUIRES_NEW` mistake

Add `@Transactional(propagation = Propagation.REQUIRES_NEW)` to `OutboxWriter.write`. Then make
`EventService.cancel` throw after writing the outbox row.

```bash
docker exec -it ebp-postgres psql -U ebp -d event_db -c \
  "SELECT event_type, aggregate_id FROM outbox_events ORDER BY id DESC LIMIT 1;"
```

**An `EventCancelled` row exists for an event that was never cancelled.** It publishes. Consumers cancel
real bookings for an event that is still running.

**A phantom event** (concept A1, case 2) — created by an annotation that *looks* more careful. Remove it
and repeat: no row, no message, nothing happened. **One annotation is the difference between the pattern
working and being worse than useless.**

### Drill 4 — multiple instances

```bash
java -jar services/event-service/target/event-service-1.0.0-SNAPSHOT.jar --server.port=8092
```

Two instances, both polling. Cancel several events and count the messages on the topic: **each appears
once**.

Now remove the `@QueryHint` for `SKIP LOCKED`, restart both, and repeat. With plain `FOR UPDATE`, one
instance blocks while the other works — correct, but serialised. Remove the lock entirely and you get
**duplicate publishing**.

**Confirm the SQL** with `logging.level.org.hibernate.SQL: DEBUG`. Seeing `for update skip locked` in
your own log is the point.

### Drill 5 — ordering under failure

Queue `EventUpdated` then `EventCancelled` for the same event, with Kafka down. Bring Kafka back and
watch the topic.

The update arrives first, then the cancellation. **Order preserved**, because the poller sorts by `id`
and the aggregate blocking stops later rows from overtaking a failed one.

Now remove the `blockedAggregates` logic and make only the *first* row fail (a payload the serialiser
rejects). The second publishes immediately, and when the first eventually succeeds it arrives **after**
the event it preceded. **A consumer would cancel and then un-cancel.** Concept A5, demonstrated.

### Drill 6 — the poison row

Insert a row with unparseable payload:

```bash
docker exec -it ebp-postgres psql -U ebp -d event_db -c \
"INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, message_id, payload, topic)
 VALUES ('Event','7','EventCancelled','poison-1','NOT JSON','event.events');"
```

Watch `attempts` climb and `next_attempt_at` stretch to five minutes. The row never blocks other
aggregates, and the log shows the running out message after ten attempts.

**Then decide what should happen next.** Retrying a incorrectly formatted payload forever is pointless; it needs a
`FAILED` state and an alert, exactly like Phase 10b's DLT. **The outbox needs its own dead-letter
thinking** — that is the natural next improvement to this code.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| `REQUIRES_NEW` on the outbox write | Phantom events | Join the caller's transaction |
| Publishing directly *and* writing the outbox | Duplicate messages | One path only |
| `messageId` generated at publish time | Dedup never matches on retry | Generate at write time |
| No partial index | The poller degrades as the table grows | `WHERE published_at IS NULL` |
| No `SKIP LOCKED` | Duplicate publishing across instances | `FOR UPDATE SKIP LOCKED` |
| Not blocking on `send()` in the poller | Rows marked published that never arrived | `.get(timeout)` here |
| One failure aborts the batch | A single bad row blocks everything | Per-row try/catch |
| No backoff on failures | A broken row is retried in a tight loop | Exponential, stored in the row |
| Never deleting published rows | The table grows forever | Retention job |
| Rebuilding the payload at publish time | The event describes *now*, not *then* | Serialise at write time |
| No backlog metric | You find out from users | Alert on unpublished count |
| Expecting exactly-once | Duplicates still happen | At-least-once + idempotent consumers |

---

## Notes for a real running system

- **Alert on pending row count and age.** One message stuck for an hour may matter more than many newly queued messages.
- **Keep failed rows inspectable.** After the retry limit, record a failed state and provide a controlled way to inspect and replay the work.
- **Consider CDC with Debezium** if measured polling load or delay becomes a problem. It also adds tools and database-log management.
- **Maintain the outbox table.** It gets frequent inserts and scans. Monitor table growth and Postgres autovacuum, the background cleanup of old row versions.
- **Tune batch size.** Larger batches reduce overhead but can hold locks longer.
- **Keep one outbox per service database.** Do not share one table across service boundaries.
- **Use the inbox-style duplicate check too.** Phase 9's `processed_messages` records incoming work completed in the same transaction as the business change. The outbox preserves outgoing work; this check prevents repeated local effects. Together they support reliable sending and once-only database effects, provided those transaction rules are followed.

---

## Phase 11 checklist

- [ ] Cancelling an event writes an outbox row in the same transaction
- [ ] The poller publishes within ~500 ms and sets `published_at`
- [ ] **Kafka down → rows queue, and everything is delivered when it returns**
- [ ] `kill -9` mid-flight loses nothing
- [ ] `REQUIRES_NEW` produced a phantom event — you saw it
- [ ] Two instances publish each row exactly once
- [ ] `for update skip locked` appears in your Hibernate SQL log
- [ ] Ordering per aggregate is preserved when a publish fails
- [ ] A poison row backs off instead of spinning
- [ ] Old published rows are cleaned up
- [ ] You can explain why 2PC is not the answer
- [ ] You can explain why the outbox gives at-least-once and never exactly-once

---

## What Phase 12 does next

Delivery is now guaranteed. The booking flow, however, is still the synchronous design from Phase 6 —
reserve over HTTP, then insert, with a written by hand compensation in a `catch` block that does not run if
the process dies.

Next: the **Saga**. Booking created as `PENDING` with an outbox row, event-service reserves seats and
replies with `SeatsReserved` or `SeatsRejected`, booking-service confirms or rejects. Compensation
becomes a durable message rather than a `catch` block. Choreography versus orchestration, and how to
debug a flow that spans two services and several seconds.

**Pre-reading for Phase 12:** the CAP theorem in simple words, and what "eventual consistency" means to a
user.
