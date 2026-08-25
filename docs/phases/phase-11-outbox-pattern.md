# Phase 11 — The Transactional Outbox

**Time:** 5–6 hours
**Needs:** Phase 10b finished.
**Pre-reading:** the dual-write problem — why a database commit and a message send cannot be atomic.

Phase 10b Drill 6: the API returned 200, the event was cancelled, the message was lost, and bookings for
a cancelled event stayed `CONFIRMED` forever. This phase fixes that class of bug permanently.

---

# PART A — Concepts

## A1. The dual-write problem

Any time one operation must change **two systems**, you have this problem.

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

**Case 3 is the one that proves you cannot fix this with error handling.** There is no `catch` for the
process being killed. A `try/finally`, a retry loop, a shutdown hook — none of them run when the
container is terminated, the machine loses power, or the JVM is OOM-killed.

**The root cause: two systems, two commits, no shared transaction.**

## A2. Why not a distributed transaction

Two-phase commit (XA) exists: a coordinator asks every participant to prepare, then tells them all to
commit.

Nobody uses it for this, for good reasons:

- **Kafka does not support XA.** Neither do most modern message brokers or HTTP APIs.
- **It blocks.** Participants hold locks through both phases. Throughput collapses.
- **The coordinator is a single point of failure.** If it dies between prepare and commit, participants
  are stuck holding locks, waiting — the "in-doubt" state.
- **It does not scale**, which is why the entire microservices industry moved to eventual consistency
  instead.

**The modern answer: never do two writes. Do one write, then derive the second from it.**

## A3. The outbox

**Write the event into your own database, in the same transaction as the business change.** One
database, one transaction, atomic by definition.

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

**Why this is watertight:**

- The business change and the intent to publish **commit together**. Impossible to have one without the
  other.
- If the process dies before publishing, the row is still there. The next poll picks it up.
- If Kafka is down for an hour, rows accumulate and are published when it returns.
- If the publish succeeds but marking it fails, the message is sent **twice** — at-least-once, which
  Phase 9 already made safe.

**The guarantee is at-least-once, never exactly-once.** That is not a weakness to apologise for; it is
the correct target, because exactly-once delivery is impossible (Phase 9, concept A3). At-least-once
delivery plus an idempotent consumer is effectively exactly-once processing.

> **Interview definition:** "The outbox turns a dual write into a single write. The event is stored in
> the same transaction as the state change, and a separate process publishes it. You trade immediate
> delivery for guaranteed delivery, and you accept duplicates — which is fine because consumers are
> idempotent."

## A4. Two ways to get rows out of the table

### Polling publisher — what we build

A scheduled job queries unpublished rows and sends them.

**Good:** simple, no extra infrastructure, works with any database, easy to debug and to test.
**Costs:** polling latency (100 ms–1 s), constant queries against the table, and you must handle
multiple instances polling at once.

### Change Data Capture (CDC) — Debezium

A connector reads the database's **write-ahead log** (Postgres logical replication) and streams changes
to Kafka directly.

**Good:** no polling load, very low latency, no application code at all.
**Costs:** Debezium and Kafka Connect to run and operate, replication slots to manage (a stalled slot
fills your disk and takes the database down), and a much steeper learning curve.

**Choose polling until the load justifies CDC.** At a few thousand events per second, polling is fine.
**Being able to explain both, and why you picked one, is what an interview is looking for** — not having
used Debezium.

## A5. Ordering, and being honest about it

The outbox can preserve ordering **per aggregate** if you publish in `id` order and key by aggregate id.
Global ordering across aggregates is neither preserved nor needed (Phase 10a: ordering is per
partition anyway).

**The subtle failure:** publish rows 1, 2, 3 in a batch; row 2's send fails; rows 1 and 3 are already
gone. Retrying row 2 sends it **after** row 3.

**Two honest options:**

1. **Stop the batch at the first failure** for that aggregate. Simple; a stuck row blocks later ones for
   the same aggregate — which is usually what you want, since they are causally related.
2. **Accept it** and make consumers order-tolerant (checking a version or timestamp in the payload).

**Our choice: option 1, per aggregate.** If `EventUpdated` cannot be published, `EventCancelled` for the
same event must wait. Publishing them out of order would be worse than publishing them late.

## A6. Multiple instances

Three booking-service instances all polling the same table:

```
instance A: SELECT unpublished → rows 1,2,3
instance B: SELECT unpublished → rows 1,2,3     ← the same rows
→ every event published three times
```

Duplicates are survivable, but three times the traffic for nothing is not. The fix is one line of SQL:

```sql
SELECT * FROM outbox_events
 WHERE published_at IS NULL
 ORDER BY id
 LIMIT 50
 FOR UPDATE SKIP LOCKED;
```

**`FOR UPDATE SKIP LOCKED` is the one to remember.** Instance A locks rows 1–50; instance B **skips
them** rather than blocking and takes 51–100. Every instance works in parallel on a different slice,
with no coordination, no distributed lock, and no leader election.

Postgres, MySQL 8+ and Oracle all support it. **This is the standard way to build a work queue in a
relational database**, and it comes up often in interviews.

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
over. A genuinely excellent thing to mention in an interview.

**The other columns:**

- `aggregate_type` / `aggregate_id` — what this event is about. `aggregate_id` becomes the **Kafka
  message key** (Phase 10a), so it decides the partition and therefore the ordering.
- `message_id` with `UNIQUE` — generated here, at write time, and carried through to the consumer's
  `processed_messages`. **Generated once, so a republished row keeps the same id and dedup works.**
- `payload TEXT` — the serialised envelope, frozen at the moment of the change. Rebuilding it later from
  the entity would capture a *different, newer* state. **The event must describe what happened, not what
  is currently true.**
- `attempts`, `last_error`, `next_attempt_at` — retry state, so one broken row does not spin forever.
- `topic` per row, so one table serves every topic this service publishes to.

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

**`messageId` is generated in the constructor**, at business-transaction time — not at publish time.
That is deliberate: if publishing fails and the row is retried, the message id is **the same**, so the
consumer's dedup recognises the duplicate. Generating it at publish time would give every retry a new
identity and defeat Phase 9 entirely.

**`markFailed` implements exponential backoff capped at 5 minutes**, stored in the row rather than held
in memory. A broken row is retried at 2 s, 4 s, 8 s … 300 s, and **the backoff survives a restart**
because it is state, not a variable. A row that fails permanently stops consuming the poller's attention
almost immediately.

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

**Turn on `logging.level.org.hibernate.SQL: DEBUG` and confirm `skip locked` appears in the generated
SQL.** If it does not, every instance will publish every row, and you will not notice until you scale
to two instances in production.

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

**Adding `@Transactional(REQUIRES_NEW)` here would destroy the pattern** — the outbox row would commit
independently and you would be back to a dual write, just with extra steps. **This is the mistake to
watch for**, because `REQUIRES_NEW` looks careful and is exactly wrong here.

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

**`fixedDelay = 500`** — half a second between the *end* of one run and the start of the next.
`fixedRate` would start runs on a timer and overlap them if one ran long. Publishing latency is
therefore up to ~500 ms, which is the cost of polling (concept A4). Lower it for tighter latency, at the
cost of more queries.

**`.get(5, TimeUnit.SECONDS)` — blocking on purpose, and this is the one exception to the rule.** Phase
10b said never block on `send()`. Here we must: **we have to know whether it succeeded before marking
the row published.** This runs on a scheduler thread, not a request thread, so blocking costs nobody
anything. **Knowing when a rule does not apply is more useful than the rule.**

**`blockedAggregates`** implements concept A5. If `EventUpdated` for event 7 fails, we skip every later
row for event 7 in this batch so ordering is preserved. Other aggregates continue unaffected.

**`markPublished` and `markFailed` need no `save()`** — the entities are managed inside `@Transactional`
and dirty checking writes them at commit (Phase 4, Drill 5).

**Failures do not abort the batch.** One bad row must not stop 49 good ones. Each row succeeds or fails
on its own.

**Exhausted rows keep being polled** with a 5-minute backoff. In production, move them to a
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

**Keep published rows for seven days**, not zero. They are an audit trail: "did we publish that event,
and when?" is a question you will be asked during an incident, and the row answers it exactly.

**The backlog is the metric that matters.** Normally 0–5. A steadily rising backlog means Kafka is down,
the poller has stopped, or something is failing repeatedly — and it is a leading indicator, visible
*before* users notice anything. In production this is a gauge in Prometheus with an alert, not a log
line.

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

**Compare directly with Phase 10b Drill 6**, which is the same scenario: there, the message vanished and
bookings for a cancelled event stayed `CONFIRMED` forever. Same failure, opposite outcome. **That
difference is the entire value of this phase.**

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
aggregates, and the log shows the exhaustion message after ten attempts.

**Then decide what should happen next.** Retrying a malformed payload forever is pointless; it needs a
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

## Production considerations

- **Alert on backlog and on age of the oldest unpublished row.** Age matters more than count: one row
  stuck for an hour is worse than a hundred rows from ten seconds ago.
- **A dead-letter state** for exhausted rows, plus an admin endpoint to inspect and replay them.
- **Consider CDC (Debezium)** if polling load or latency becomes a problem — and be able to explain the
  operational cost you would be taking on.
- **The outbox table is hot.** It is written on every business transaction and polled constantly. Watch
  for bloat and ensure autovacuum keeps up.
- **Batch size is a tuning knob** — larger batches are more efficient and hold locks longer.
- **One outbox table per service**, never shared. It is part of that service's database.
- **The inbox pattern** is the mirror image: record incoming message ids in a table (Phase 9's
  `processed_messages`) so consumers are idempotent. Outbox guarantees *sending*; inbox guarantees
  *processing once*. **Together they give end-to-end exactly-once processing** — and that sentence is a
  complete answer to one of the hardest interview questions in this space.

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
reserve over HTTP, then insert, with a hand-rolled compensation in a `catch` block that does not run if
the process dies.

Next: the **Saga**. Booking created as `PENDING` with an outbox row, event-service reserves seats and
replies with `SeatsReserved` or `SeatsRejected`, booking-service confirms or rejects. Compensation
becomes a durable message rather than a `catch` block. Choreography versus orchestration, and how to
debug a flow that spans two services and several seconds.

**Pre-reading for Phase 12:** the CAP theorem in plain terms, and what "eventual consistency" means to a
user.
