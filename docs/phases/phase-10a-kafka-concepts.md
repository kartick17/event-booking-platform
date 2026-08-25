# Phase 10a — Kafka concepts

**Time:** 3–4 hours (concepts + hands-on CLI, no application code)
**Needs:** Phase 9 finished. Kafka running from Phase 1.
**Pre-reading:** log-based messaging versus queue-based, in one paragraph.

No Java in this doc. You will drive Kafka from the command line until the model is clear, then write
producers and consumers in Phase 10b. Writing Kafka code before understanding partitions is how people
end up with systems that lose ordering and cannot explain why.

---

# PART A — Concepts

## A1. What a message broker buys you

Phase 6's arithmetic: booking-service calling event-service synchronously means
`0.99 × 0.99 = 98%` availability, added latency, and failure propagating backwards.

With a broker in the middle:

```
SYNCHRONOUS
booking ──HTTP──▶ event          both must be up at the same instant

ASYNCHRONOUS
booking ──▶ [ Kafka ] ──▶ event  event can be down for an hour and lose nothing
```

**What actually changes:**

| | Sync | Async |
|---|---|---|
| Both parties up at once | required | not required |
| Caller waits | yes | no |
| Failure propagates back | yes | no |
| Adding a third consumer | change the producer | change nothing |
| Ordering | trivial | needs design |
| Debugging | one stack trace | spread across services and time |
| Consistency | immediate | eventual |

**The last row is the price.** With a broker, "the booking exists" and "the seats are reserved" are true
at *slightly different times*. Everything in Phases 11 and 12 is about making that gap safe.

**The "adding a third consumer" row is the underrated one.** Today `BookingCreated` reduces seats.
Tomorrow you want emails, then analytics, then fraud checks. Synchronously that is three edits to
booking-service and three more sync dependencies. With Kafka, three new consumers subscribe and
booking-service never learns they exist.

## A2. Commands and events, domain and integration

Two distinctions that keep event-driven systems from turning into RPC with extra steps.

**Command** — "do this." Directed at one handler, may be rejected, imperative name: `ReserveSeats`.

**Event** — "this happened." A fact, in the past tense, already true, aimed at nobody in particular:
`BookingCreated`. Anyone may listen. Nobody may reject it.

**Publish events, not commands, between services.** A command over a broker is a synchronous call
wearing a disguise: the sender still knows who must act and still cares about the outcome — the coupling
survives, and you lost the stack trace.

**Domain event vs integration event** — the distinction that saves you later:

- **Domain event** — internal to one service. Rich, tied to your model, changes freely.
  `SeatsReserved(Event aggregate, ...)`.
- **Integration event** — crosses the service boundary. **It is a public API contract.** Minimal, stable,
  versioned, no internal types.

```
Event aggregate → domain event (internal) → mapped → integration event (published)
```

Publishing your domain events directly is the same mistake as returning your JPA entity from a
controller. Everything you emit becomes something you cannot change.

**Naming: past tense, always.** `BookingCreated`, not `CreateBooking`. If the name reads as an
instruction, you have a command and you should ask why it is on a topic.

## A3. Kafka's model

Kafka is **not a queue**. It is a **distributed, append-only, replayable log**.

### The log

```
Topic: booking.events
Partition 0:  [0][1][2][3][4][5][6]  ← append-only, never modified
                          ▲       ▲
                consumer-A       consumer-B
                offset=3         offset=6
```

**Messages are not deleted when read.** They stay for the retention period. Each consumer group tracks
its own **offset** — its position in the log.

Consequences that make Kafka different from RabbitMQ or SQS:

- **Many independent consumers** read the same messages at their own pace.
- **Replay** — reset the offset to 0 and reprocess a week of history. Priceless for fixing a consumer bug.
- **Reads are sequential disk reads**, which is why it is fast despite writing everything to disk.

### Broker, cluster, topic, partition

- **Broker** — one Kafka server. Ours is a single-node cluster from Phase 1.
- **Topic** — a named stream: `booking.events`.
- **Partition** — a topic is split into partitions. **This is the unit of parallelism and of ordering.**
- **Offset** — a message's position within one partition. Unique per partition, not global.

```
Topic: booking.events (3 partitions)
  P0: [0][1][2][3]
  P1: [0][1][2]
  P2: [0][1][2][3][4]
```

**Kafka guarantees order *within a partition*, and gives no ordering guarantee across partitions.**

Everything else about partitions follows from that one sentence.

### The message key decides the partition

```
partition = murmur2(key) % numberOfPartitions
```

Same key → same partition → **ordered relative to each other**. No key → round robin → no ordering.

**This is the most important design decision you make with Kafka.** For our events the key is the
**event id**, so every message about event 42 lands in one partition and is processed in order.

Consider what happens without it:

```
Partition 0:  SeatsReserved(event=42, qty=2)
Partition 1:  EventCancelled(event=42)
```

Two consumers, two partitions, no ordering between them. The cancellation may be processed before the
reservation. **The bug is not in your code — it is in your partition key**, and it will be intermittent
and nearly impossible to reproduce.

**Choosing the key means choosing what must stay ordered.** Key by event id and all messages for one
event are ordered while different events process in parallel. That is exactly the trade you want.

**The cost:** a very hot key (one enormously popular event) sends all its traffic to one partition, and
that partition becomes your bottleneck. Ordering and parallelism are genuinely in tension.

### Consumer groups

```
Topic with 3 partitions

Group "event-service" with 2 consumers:
  consumer-1 → P0, P1
  consumer-2 → P2

Group "analytics" with 1 consumer:
  consumer-1 → P0, P1, P2      ← reads the SAME messages independently
```

**Within a group:** each partition goes to exactly one consumer. Work is divided.
**Across groups:** every group gets every message. Work is duplicated by design — this is pub/sub.

**The rule that surprises people: consumers beyond the partition count sit idle.** Three partitions and
five consumers means two do nothing. **Partition count is your maximum parallelism**, chosen when the
topic is created and awkward to raise later (raising it changes `hash % n` and therefore breaks existing
key→partition assignments).

`group.id` is what identifies a group. **Two services accidentally sharing a `group.id` will each
receive only half the messages**, and it looks like random message loss. Name groups after the service.

### Rebalancing

When a consumer joins, leaves, or dies, partitions are reassigned.

```
before:  c1 → P0,P1   c2 → P2
c2 dies
after:   c1 → P0,P1,P2
```

During a rebalance, **consumption stops** ("stop the world"). Long rebalances are a real production
problem. The usual cause: a consumer takes longer than `max.poll.interval.ms` (default 5 minutes) to
process a batch, so the broker assumes it is dead and rebalances — the consumer then finds its
partitions revoked mid-work.

**Slow consumers cause rebalance storms.** Keep processing fast, or lower `max.poll.records`.

## A4. Producer guarantees

### acks

| Setting | Meaning | Risk |
|---|---|---|
| `acks=0` | do not wait at all | messages silently lost |
| `acks=1` | leader wrote it | lost if the leader dies before replication |
| `acks=all` | leader **and** all in-sync replicas wrote it | slowest, safest |

**`acks=all` combined with `min.insync.replicas=2`** is the durable configuration: a write must reach at
least two replicas or it fails. With `replication.factor=3` you survive one broker loss with no data
loss.

**Our single-node dev cluster has `replication.factor=1`** (Phase 1), so `acks=all` is effectively
`acks=1`. Set it correctly anyway — config that is wrong in dev is config that ships.

### The idempotent producer

`enable.idempotence=true` (the default in modern clients) makes the **broker** deduplicate retries from
one producer session, using a producer id and a sequence number per partition.

**It solves exactly one problem: a producer retry writing the same message twice.** It does **not** make
your consumer idempotent, and it does not deduplicate two genuinely separate `send()` calls with the
same content. Phase 9's consumer-side dedup is still required. People conflate these constantly.

### Batching

The producer accumulates messages for up to `linger.ms` and sends them as one batch. Higher `linger.ms`
= better throughput, worse latency. **The `send()` call is asynchronous** — it returns a future, and the
message is still in a buffer.

**This matters enormously in Phase 11.** A `send()` that "succeeded" from your code's point of view may
not be on the broker yet. If the process dies, it is gone. That is half the dual-write problem.

## A5. Consumer guarantees and the commit order

The poll loop:

```
while (true) {
    records = consumer.poll(timeout)      // fetch a batch
    for (record : records) process(record)
    consumer.commitSync()                 // save the offset
}
```

**Where you commit decides your delivery guarantee, and there is no third option:**

**Commit *after* processing → at-least-once.** Crash between processing and commit → the message is
redelivered → processed twice. **Duplicates.**

**Commit *before* processing → at-most-once.** Crash after commit, before processing → never
redelivered. **Loss.**

```
at-least-once:  poll → process → commit     duplicates possible
at-most-once:   poll → commit → process     loss possible
```

**Choose at-least-once, always, for anything that matters** — then make the consumer idempotent (Phase
9). Losing a booking is unacceptable; processing one twice is merely a problem you already solved.

**Auto-commit (`enable.auto.commit=true`) is the trap.** It commits on a timer, unrelated to your
processing, so you get *both* failure modes depending on timing. Spring Kafka defaults to manual
acknowledgement modes for this reason.

### Kafka's "exactly-once"

Kafka does offer exactly-once semantics — transactions spanning consume-process-produce **within
Kafka**. Read a message, produce a result, commit the offset, all atomically.

**It does not extend to your database.** The moment your consumer writes to Postgres, that write is
outside Kafka's transaction. So for us: **at-least-once delivery + idempotent consumer** (Phase 9,
concept A3). Say that sentence in an interview and you have answered the question properly.

## A6. Retention, compaction, and replay

**Retention** — how long messages are kept. `retention.ms` (default 7 days) or `retention.bytes`. After
that they are deleted regardless of whether anyone read them.

**Compaction** (`cleanup.policy=compact`) — instead of deleting by age, keep **the latest message per
key** forever. The topic becomes a snapshot of current state rather than a history of changes. Perfect
for "current price of every event"; wrong for "everything that happened".

**Replay** is Kafka's superpower and worth internalising:

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group event-service --topic booking.events --reset-offsets --to-earliest --execute
```

Ship a consumer bug, fix it, reset the offset, reprocess a week of events. **Only safe if your consumer
is idempotent** — which is why Phase 9 came first. Replay against a non-idempotent consumer duplicates a
week of data.

## A7. Failure handling: retries and the dead letter topic

A consumer throws. Then what?

**Blocking retry** — retry in place, with backoff. Simple, but the partition is **stuck**: nothing behind
that message is processed. One poison message halts a whole partition.

**Non-blocking retry** — publish to a retry topic with a delay and move on. Ordering within the
partition is lost for that message. Spring Kafka's `@RetryableTopic` implements this.

**Dead Letter Topic (DLT)** — after N failed attempts, publish to `booking.events.DLT` and commit the
offset so the partition moves on.

```
booking.events ──fail──▶ booking.events.retry.1s ──fail──▶ .retry.5s ──fail──▶ .DLT
```

**A DLT is not a bin. It is an inbox.** Messages there represent work that did not happen. **Alert on
it.** A DLT nobody monitors is silent data loss with extra steps.

**Distinguish the two failure kinds before choosing:**

- **Transient** (database down, network blip) — retry. It will work later.
- **Permanent** (malformed message, an event id that never existed) — retrying forever is pointless.
  Straight to the DLT.

## A8. Topic design for our system

**Naming:** `<domain>.<type>` — `booking.events`, `event.events`. Consistent, greppable, and it groups
in tooling. Some teams add the version: `booking.events.v1`.

**Granularity — one topic per aggregate, not per event type.** `booking.events` carries
`BookingCreated`, `BookingCancelled` and `BookingConfirmed`, discriminated by a header or a `type`
field.

Why: **ordering is per partition, and per partition means per topic.** `BookingCreated` and
`BookingCancelled` on separate topics have no ordering relative to each other, so a cancellation can be
processed before the creation it cancels. One topic per aggregate keeps a single ordered stream per
entity.

**Our topics:**

| Topic | Key | Produced by | Consumed by | Carries |
|---|---|---|---|---|
| `booking.events` | eventId | booking-service | event-service | `BookingCreated`, `BookingCancelled` |
| `event.events` | eventId | event-service | booking-service | `SeatsReserved`, `SeatsRejected`, `EventCancelled` |

**Both keyed by `eventId`, not `bookingId`** — deliberately. Seat availability is contested per *event*,
so all messages about one event must be ordered relative to each other. Keying by booking id would let
two bookings for the same event be processed in parallel by different consumers, which is exactly the
ordering we need to preserve.

**Event payload design — thin or fat:**

- **Thin** (`{bookingId: 55}`) — small, but every consumer must call back for details, recreating the
  synchronous coupling you were escaping.
- **Fat** (all the fields a consumer needs) — self-contained, replayable, no callback.

**Prefer fat events**, containing what consumers need and nothing internal. A fat event is a fact frozen
in time; a thin one is a pointer to mutable state that may have changed by the time it is read.

**Schema and versioning — this is a public contract:**

- Adding an optional field: safe.
- Removing or renaming a field: breaking. Old consumers are still running, and old messages are still in
  the log.
- **Always include `eventId` (a unique message id), `eventType`, `occurredAt` and a schema `version`.**
  The message id is what Phase 9's `processed_messages` deduplicates on.

For real systems: a schema registry (Avro or Protobuf) enforces compatibility at publish time. JSON with
discipline is fine here, and the discipline is the point.

## A9. When not to use Kafka

- **Request/response.** If you need an answer now, make an HTTP call.
- **Low volume, simple routing.** RabbitMQ or SQS is simpler to run and reason about.
- **Per-message priority or selective consumption.** Kafka reads a log in order; it has no priority
  queue and no "give me the messages matching X".
- **Very low latency single messages.** Batching adds milliseconds.
- **When one service and one consumer would do.** Kafka is a distributed system you now have to operate.

**Kafka vs RabbitMQ in one line:** RabbitMQ is a *broker* that routes messages to consumers and deletes
them; Kafka is a *log* that stores them and lets consumers track their own position. Replay, multiple
independent consumers and high throughput favour Kafka; complex routing, priorities and per-message
acknowledgement favour RabbitMQ.

---

# PART B — Drive Kafka by hand

Kafka is running from Phase 1. Everything below is CLI — build the mental model before writing code.

Shorthand for the container:

```bash
alias kafka='docker exec -it ebp-kafka /opt/kafka/bin'
```

## Step 1 — Create a topic and look at it

```bash
kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic booking.events --partitions 3 --replication-factor 1

kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic booking.events
```

```
Topic: booking.events  PartitionCount: 3  ReplicationFactor: 1
  Topic: booking.events  Partition: 0  Leader: 1  Replicas: 1  Isr: 1
  Topic: booking.events  Partition: 1  Leader: 1  Replicas: 1  Isr: 1
  Topic: booking.events  Partition: 2  Leader: 1  Replicas: 1  Isr: 1
```

**Read it:** three independent logs. `Leader: 1` — broker 1 handles all reads and writes for each
partition. `Isr` (in-sync replicas) has one entry because we have one broker. In production this would
be `Isr: 1,2,3`, and `acks=all` would mean "all three wrote it".

**Three partitions means at most three consumers in a group can work in parallel.**

## Step 2 — Watch keys choose partitions

Producer:

```bash
kafka/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic booking.events --property "parse.key=true" --property "key.separator=:"
```

Consumer, in another terminal:

```bash
kafka/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic booking.events --from-beginning \
  --property print.key=true --property print.partition=true --property print.offset=true
```

Type into the producer:

```
1:{"type":"BookingCreated","eventId":1,"qty":2}
1:{"type":"BookingCancelled","eventId":1}
2:{"type":"BookingCreated","eventId":2,"qty":1}
1:{"type":"BookingCreated","eventId":1,"qty":3}
```

The consumer output shows every message with key `1` in the **same partition** with increasing offsets,
and key `2` somewhere else.

**That is concept A3, proven.** Same key → same partition → guaranteed order. Different keys → parallel,
no ordering between them.

**Now the counter-example.** Send without keys:

```bash
kafka/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic booking.events
```

Type five messages. They scatter across partitions round-robin. **If those five concerned one booking,
their order is now undefined.** Nothing errors. You would find out in production.

## Step 3 — Consumer groups

Run two consumers **in the same group**, in two terminals:

```bash
kafka/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic booking.events --group demo-group --property print.partition=true
```

Produce ten keyed messages. **Each message goes to exactly one of the two consumers** — partitions are
divided between them.

Now start a **third** consumer in the same group. With three partitions each gets one. Start a
**fourth**: it sits idle forever.

**That is the partition-count ceiling.** More consumers than partitions is wasted capacity.

Now start a consumer with a **different** group:

```bash
kafka/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic booking.events --group analytics --from-beginning
```

It receives **everything**, independently, from the beginning. **Pub/sub across groups, work-sharing
within a group** — one mechanism, two behaviours, and the only difference is a string.

## Step 4 — Watch a rebalance

With two consumers running in `demo-group`, kill one with Ctrl-C and watch the other's log:

```
Revoke previously assigned partitions
Successfully joined group with generation 4
Notifying assignor about the new Assignment: partitions=[0,1,2]
```

Consumption paused, partitions were reassigned, consumption resumed. Now imagine that happening every
few minutes because your consumer is slow — that is a rebalance storm (concept A3).

## Step 5 — Offsets, lag, and replay

```bash
kafka/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group demo-group
```

```
GROUP       TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
demo-group  booking.events  0          4               4               0
demo-group  booking.events  1          3               7               4
```

**`LAG` is the single most important operational metric in Kafka.** How far behind the consumer is.
Growing lag means the consumer cannot keep up, and it is what you alert on.

**Now replay.** Stop the consumers, then:

```bash
kafka/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group demo-group --topic booking.events --reset-offsets --to-earliest --execute
```

Restart a consumer: **every message arrives again**. Nothing was deleted, so nothing was lost.

**Sit with what this makes possible.** A consumer bug that corrupted a week of data is fixable: deploy
the fix, reset, reprocess. No other messaging system gives you that. And it only works because your
consumer is idempotent — Phase 9, doing its job.

## Step 6 — Retention and compaction

```bash
kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --alter --topic booking.events --config retention.ms=60000

kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic booking.events --describe-configs 2>/dev/null | head -5
```

One-minute retention. Produce messages, wait, and consume `--from-beginning`: the old ones are gone.

**Retention is a data-loss policy you choose.** Seven days is the default; if a consumer is down longer
than that, it misses messages permanently. **Alert on consumer downtime approaching your retention
window.**

Create a compacted topic to see the difference:

```bash
kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic event.snapshot --partitions 1 --replication-factor 1 \
  --config cleanup.policy=compact
```

Send `1:{"seats":100}` then `1:{"seats":98}` then `1:{"seats":95}`. After compaction only the last
message for key `1` survives. **A history topic versus a state topic** — one keeps everything that
happened, the other keeps what is currently true.

Set retention back to something sane before moving on.

## Step 7 — Create the real topics

```bash
kafka/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic booking.events
kafka/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic demo-group 2>/dev/null

kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic booking.events --partitions 3 --replication-factor 1
kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic event.events --partitions 3 --replication-factor 1
kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic booking.events.DLT --partitions 1 --replication-factor 1
kafka/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --topic event.events.DLT --partitions 1 --replication-factor 1

kafka/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

**DLTs get one partition** — ordering is irrelevant there, and volume should be near zero. If a DLT
needs more than one partition, something is very wrong upstream.

Auto-creation of topics exists (`auto.create.topics.enable`) and should be **off in production**: a typo
in a topic name silently creates a new topic that nobody consumes, and the messages vanish into it.

---

## Common misunderstandings

| Belief | Reality |
|---|---|
| Kafka is a queue | It is a replayable log; messages survive being read |
| Kafka guarantees ordering | Only **within one partition** |
| More consumers = more throughput | Capped by partition count |
| Exactly-once is available end-to-end | Only within Kafka; your database side effects are outside it |
| Idempotent producer makes consumers safe | It only dedups producer retries |
| A message is delivered once | At-least-once means duplicates are normal |
| `send()` returning means it is stored | It may still be in the producer buffer |
| Consuming deletes the message | Retention deletes it, on a timer |
| Adding partitions is harmless | It changes `hash % n` and breaks existing key→partition mapping |
| A DLT is a bin | It is unfinished work; alert on it |
| Auto-commit is convenient | It gives you loss *and* duplicates, depending on timing |

---

## Production considerations

- **`replication.factor=3`, `min.insync.replicas=2`, `acks=all`.** Anything less risks data loss on a
  broker failure.
- **Alert on consumer lag**, per group per partition. It is your early warning for everything.
- **Alert on DLT depth.** Non-zero means work is not happening.
- **Turn off topic auto-creation.** Create topics deliberately, with reviewed configuration.
- **Choose partition count with room to grow** — increasing it later breaks key affinity.
- **Schema registry** (Avro/Protobuf) for real contracts, or JSON with strict, reviewed rules.
- **Monitor rebalance frequency.** Frequent rebalances usually mean slow consumers.
- **Retention longer than your worst realistic outage.**
- **Do not put personal data in events you keep forever** — GDPR deletion and infinite retention are
  hard to reconcile. Compacted topics with tombstones are one answer.

---

## Phase 10a checklist

- [ ] You created a topic and read `--describe` output
- [ ] You saw the same key always land in the same partition
- [ ] You saw unkeyed messages scatter and lose ordering
- [ ] Two consumers in one group split partitions; a third group got everything
- [ ] A consumer beyond the partition count sat idle
- [ ] You triggered a rebalance and read the log lines
- [ ] You read consumer lag and understand why it is the key metric
- [ ] You reset offsets and replayed messages
- [ ] You saw compaction keep only the latest value per key
- [ ] The four real topics exist
- [ ] You can explain at-least-once vs at-most-once in terms of **when you commit**
- [ ] You can explain why our events are keyed by `eventId`
- [ ] You can say why "exactly-once" does not extend to your database

---

## What Phase 10b does next

Now the code. Spring Kafka producers and consumers, JSON serialisation, the event envelope
(`messageId`, `type`, `occurredAt`, `version`), manual acknowledgement, error handling with
`@RetryableTopic` and a DLT, and the idempotent consumer from Phase 9 wired to `processed_messages`.

First events: `EventCreated` and `EventUpdated` — genuinely one-way notifications, so we can learn the
plumbing before Phase 12 makes the booking flow depend on it.

**Pre-reading for Phase 10b:** none. Re-read your notes on partitions and keys instead.
