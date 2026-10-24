# Phase 10a — Kafka concepts

**Time:** 3–4 hours (concepts + hands-on CLI, no application code)
**Needs:** Phase 9 finished. Kafka running from Phase 1.
**Pre-reading:** log-based messaging versus queue-based, in one paragraph.

This guide uses terminal commands rather than Java. First, see how Kafka stores messages in partitions and how consumers track their place. Phase 10b then adds producers and consumers to the services.

---

## In plain English

This phase teaches Kafka using terminal commands before adding Java code. Kafka stores messages so services can read and handle them later. You will see where messages go, who reads them, and how readers keep their place.

Example: booking-service sends a BookingCreated message. Event-service reads it and handles the seats. A separate email service could read the same message independently. Reading does not remove the message from Kafka.

Words used in this phase:

- **Broker:** a Kafka server that stores messages.
- **Producer / consumer:** an application that sends messages / reads messages.
- **Topic:** a named stream of messages, such as booking.events.
- **Partition:** one ordered part of a topic. A topic can have several parts to spread work.
- **Key:** a value used to choose the partition for a message.
- **Offset:** a message's position in one partition.
- **Consumer group:** consumers that divide a topic's partitions between them. Different groups read independently.
- **Commit an offset:** save how far a consumer group has read.
- **Lag:** how far the consumer is behind the messages already written.
- **Retention / replay:** how long messages are kept / reading stored messages again.
- **Rebalance:** moving partition assignments between consumers when group membership changes.

Start with a topic, partition, and offset. Understand those three before the producer settings. Then follow the terminal exercises in order.

---

# PART A — Understand the ideas

## A1. What a message broker buys you

In Phase 6, booking-service waited for event-service to answer. That added waiting time and required both services to work during the request. Messages let us separate when the request is accepted from when the seat work finishes.

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
| Failure spreads back | yes | no |
| Adding a third consumer | change the producer | change nothing |
| Ordering | simple | needs design |
| Debugging | one stack trace | spread across services and time |
| Consistency | immediate | eventual |

**The cost is delayed agreement.** A booking may exist before its seats have been reserved. Phases 11 and 12 make that period safe and show the user that confirmation is still pending.

**New services can react without changing booking-service.** For example, email and reporting services can each read `BookingCreated`. Booking-service does not need to call them one after another.

## A2. Commands and events, domain and integration

First separate instructions from facts. Then separate messages used only inside a service from messages shared between services.

**Command:** asks a receiver to do something. `ReserveSeats` is an instruction. The receiver may accept or reject it.

**Event:** reports something that already happened. `BookingCreated` means the booking was created. Other services may react to the fact, but cannot change whether it happened.

**This project uses events between services.** A message such as `BookingCreated` reports a fact without calling a particular receiver. Commands over messaging are also a design choice, but they keep an explicit expectation about which service should act.

**Domain event vs integration event** — the distinction that saves you later:

- **Domain event:** used inside one service. It can refer to internal model objects, such as `SeatsReserved(Event aggregate, ...)`.
- **Integration event:** sent to other services. It is an agreed data format, kept small and stable, without internal Java types.

```
Event aggregate → domain event (internal) → mapped → integration event (published)
```

If you publish an internal domain object, consumers can become dependent on its fields and structure. Use a separate message format so internal classes can change without unexpectedly breaking another service.

**Naming: past tense, always.** `BookingCreated`, not `CreateBooking`. If the name reads as an
instruction, you have a command and you should ask why it is on a topic.

## A3. Kafka's model

Kafka stores messages in a **log**: new records are added at the end, and existing records can be read again while retained. It spreads the log across servers and partitions. Reading a record does not remove it.

### The log

```
Topic: booking.events
Partition 0:  [0][1][2][3][4][5][6]  ← append-only, never modified
                          ▲       ▲
                consumer-A       consumer-B
                offset=3         offset=6
```

**Reading does not delete a message.** Kafka keeps it according to the topic's storage rules. Each consumer group saves its own offset, so groups can read the same records at different speeds.

Consequences that make Kafka different from RabbitMQ or SQS:

- **Independent groups** can read the same messages at their own speed.
- **Replay:** move the reading position back and process retained messages again, for example after fixing a bug.
- **Sequential reads:** reading records in order helps Kafka handle large amounts of data efficiently.

### Broker, cluster, topic, partition

- **Broker:** one Kafka server. Locally we run one broker from Phase 1.
- **Topic:** a named stream, such as `booking.events`.
- **Partition:** one ordered part of a topic. Different partitions can be handled at the same time.
- **Offset:** a record's position within one partition. It is not a position across all topics or partitions.

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

With a stable partition setup, the same key goes to the same partition. Records in that partition have an order. Records without a shared key do not give us the per-event ordering this application needs.

**The key decides which messages stay together.** We use the event id, so messages for event 42 go to the same partition and can be read in that partition's order.

Consider what happens without it:

```
Partition 0:  SeatsReserved(event=42, qty=2)
Partition 1:  EventCancelled(event=42)
```

If related messages go to different partitions, consumers can handle a cancellation before the reservation it refers to. The code may look correct, but the message order is wrong for the workflow.

**Choose the key from the work that needs ordering.** Here, messages about one event should stay together. Messages about different events can be handled at the same time.

**A popular event can overload one partition.** Keeping all its messages ordered also limits how much of that work can run in parallel. More partitions do not automatically split one key's work.

### Consumer groups

```
Topic with 3 partitions

Group "event-service" with 2 consumers:
  consumer-1 → P0, P1
  consumer-2 → P2

Group "analytics" with 1 consumer:
  consumer-1 → P0, P1, P2      ← reads the SAME messages independently
```

**Within one group:** consumers divide partitions, so they share the work.
**Between different groups:** each group reads the messages independently, so event handling and email handling can both see the same record.

**Extra consumers do not always add capacity.** In the group model used here, one partition belongs to one consumer at a time. With 3 partitions and 5 consumers, 2 consumers have no partition to read. Adding partitions later can change which partition a key uses.

**Give independent services different `group.id` values.** Services that accidentally share a group divide the records instead of each reading them all. The missing records can then look like message loss.

### Rebalancing

When a consumer joins, leaves, or dies, partitions are reassigned.

```
before:  c1 → P0,P1   c2 → P2
c2 dies
after:   c1 → P0,P1,P2
```

When partitions are reassigned, processing can pause. If processing takes longer than `max.poll.interval.ms` (5 minutes in this example), the consumer may lose its assignment. Another consumer can then take over, even though the old one was still doing work.

**Keep processing times under control.** Smaller batches through `max.poll.records` can help a slow consumer keep polling in time and avoid repeated reassignment.

## A4. Producer guarantees

### acks

| Setting | Meaning | Risk |
|---|---|---|
| `acks=0` | do not wait at all | messages without a clear error lost |
| `acks=1` | leader wrote it | lost if the leader dies before replication |
| `acks=all` | leader **and** all in-sync replicas wrote it | slowest, safest |

**`acks=all` asks Kafka to acknowledge after the current in-sync replicas have accepted the record.** With `min.insync.replicas=2` and `replication.factor=3`, writes need at least two in-sync copies. This protects against one broker loss under the expected failure conditions.

**Our single-node dev cluster has `replication.factor=1`** (Phase 1), so `acks=all` is effectively
`acks=1`. Set it correctly anyway — config that is wrong in dev is config that ships.

### The idempotent producer

`enable.idempotence=true` lets Kafka recognise retries by the producer using a producer id and sequence numbers. This prevents those retries from adding the same record again.

**Producer duplicate protection has a limited job.** It does not stop your application from making two separate `send()` calls, and it does not stop a consumer from applying a database change twice. We still need the checks from Phase 9.

### Batching

The producer can collect messages briefly, up to `linger.ms`, and send them together. Bigger batches can handle more messages per second but add waiting time. `send()` returns a future; returning from the method does not prove Kafka has saved the message.

**Keep this in mind for Phase 11:** the message may still be in application memory after `send()` returns. If the process stops before Kafka accepts it, that message can be lost.

## A5. Consumer guarantees and the commit order

The poll loop:

```
while (true) {
    records = consumer.poll(timeout)      // fetch a batch
    for (record : records) process(record)
    consumer.commitSync()                 // save the offset
}
```

**The order of processing and saving the offset decides what happens after a crash:**

**Save the offset after processing:** a crash between the work and offset save can cause the record to be delivered again. The work must be safe to repeat. This is at-least-once handling.

**Save the offset before processing:** a crash after the offset save can leave work undone, and the group has already moved past the record. This can lose work.

```
at-least-once:  poll → process → commit     duplicates possible
at-most-once:   poll → commit → process     loss possible
```

**For bookings, allow repeat delivery and prevent duplicate changes.** A missed booking is unacceptable; the same message arriving again is manageable with Phase 9's saved message ids.

**Do not use timer-based auto-commit for this flow.** `enable.auto.commit=true` can advance the reading position without matching the completion of your work. Choose a Spring Kafka acknowledgement mode that commits after processing, as Phase 10b demonstrates.

### Kafka's "exactly-once"

Kafka transactions can group reading, producing new Kafka records, and saving offsets into one operation within Kafka. This is the boundary of Kafka's exactly-once support.

**A Postgres write is outside that Kafka transaction.** Our consumers therefore still need repeat delivery plus duplicate checks saved with their database work, as described in Phase 9.

## A6. Retention, compaction, and replay

**Retention** is how long, or how much, Kafka keeps. `retention.ms` sets a time limit (7 days in this example); `retention.bytes` sets a size limit. Records can be removed even if a consumer has not read them.

**Compaction** (`cleanup.policy=compact`) removes older values for the same key over time, keeping the latest retained value. This helps build a current view, such as the latest event price. It is unsuitable when every historical change must remain.

**Replay** means moving the consumer's position back and reading retained records again:

```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group event-service --topic booking.events --reset-offsets --to-earliest --execute
```

After fixing a consumer bug, you can read past messages again. Make the database effects safe to repeat first, or replay can duplicate earlier changes.

## A7. Failure handling: retries and the dead letter topic

A consumer throws. Then what?

**Blocking retry:** retry the failing message before continuing. This preserves its place in the order, but later messages in that partition wait too. A message that always fails can stop progress.

**Non-blocking retry:** move the message to a retry topic and continue with later records. `@RetryableTopic` provides this in Spring Kafka. The retried record may then be handled after records originally behind it.

**Dead Letter Topic (DLT):** after the allowed failures, put the record in a separate topic such as `booking.events.DLT` and let the main partition continue. The failed work still needs attention.

```
booking.events ──fail──▶ booking.events.retry.1s ──fail──▶ .retry.5s ──fail──▶ .DLT
```

**Monitor the DLT.** A record there means some work did not finish. Someone or a recovery process must inspect it, fix the cause, and decide whether to retry it.

**Distinguish the two failure kinds before choosing:**

- **Temporary** (database down, network blip) — retry. It will work later.
- **Permanent** (incorrectly formatted message, an event id that never existed) — retrying forever is pointless.
  Straight to the DLT.

## A8. Topic design for our system

**Naming:** `<domain>.<type>` — `booking.events`, `event.events`. Consistent, greppable, and it groups
in tooling. Some teams add the version: `booking.events.v1`.

**Group related event types in a topic.** `booking.events` contains `BookingCreated`, `BookingCancelled`, and `BookingConfirmed`. A field or header identifies which type a record contains.

**Separate topics do not provide one shared order.** Putting creation and cancellation in the same topic with the same key lets a consumer read them in partition order. Putting them on separate topics removes that ordering guarantee.

**Our topics:**

| Topic | Key | Produced by | Consumed by | Carries |
|---|---|---|---|---|
| `booking.events` | eventId | booking-service | event-service | `BookingCreated`, `BookingCancelled` |
| `event.events` | eventId | event-service | booking-service | `SeatsReserved`, `SeatsRejected`, `EventCancelled` |

**We key both topics by `eventId`, not `bookingId`.** Seat changes compete for the same event. Messages for that event must stay together even when they concern different bookings.

**Event payload design — thin or fat:**

- **Thin** (`{bookingId: 55}`) — small, but every consumer must call back for details, recreating the
  synchronous coupling you were escaping.
- **Fat** (all the fields a consumer needs) — self-contained, replayable, no callback.

**Include the fields needed to handle the event.** A message containing only an id can force the consumer to make another waiting call for details. A self-contained message also keeps the values as they were when the event happened.

**Schema and versioning — this is a public contract:**

- **Adding an optional field** usually keeps old consumers working.
- **Removing or renaming a field** can break running consumers and replay of older records.
- **Include a unique message id, `eventType`, `occurredAt`, and a format `version`.** Some designs call the message id `eventId`; this project's envelope uses `messageId` and keeps the business event id separately. The unique message id supplies the duplicate check in `processed_messages`.

In a larger system, a **schema registry** records message formats and checks whether changes are compatible. Avro and Protobuf are common formats used with it. This project uses JSON and reviewed rules for changing fields.

## A9. When not to use Kafka

- **An immediate answer is required:** an HTTP request may be simpler.
- **Only a small amount of work needs simple routing:** consider whether RabbitMQ or SQS is easier to operate.
- **Each message needs priority or special selection:** Kafka's ordered partition log is not a priority queue.
- **Single-message delay must be extremely small:** batching can add waiting time.
- **One simple service would solve the problem:** adding Kafka also adds a system to run, monitor, and recover.

**Main difference:** a typical RabbitMQ work queue removes messages after acknowledgement. Kafka keeps records according to its storage rules, and each consumer group keeps its own reading position. Choose based on whether the job needs replay and independent readers or more specialised per-message routing.

---

# PART B — Drive Kafka by hand

Kafka is already running from Phase 1. **CLI** means command-line interface: you will use terminal commands below to see its behaviour before adding application code.

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

**The output describes three separate partition logs.** `Leader: 1` means broker 1 leads that partition. `Isr` lists copies currently in sync; our single-broker setup has only one. A production list could show `Isr: 1,2,3`, with `acks=all` waiting for the current in-sync copies.

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

**Replay helps repair processing bugs.** After fixing the consumer, move its offset back and read the retained records again. The business effects must be safe to repeat, as in Phase 9, or replay can create duplicate changes.

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

Send `1:{"seats":100}`, then `1:{"seats":98}`, then `1:{"seats":95}`. After compaction catches up, the newest value for key `1` remains. A history topic keeps changes; a compacted topic helps keep the latest value for each key.

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
in a topic name without a clear error creates a new topic that nobody consumes, and the messages vanish into it.

---

## Common misunderstandings

| Belief | Reality |
|---|---|
| Kafka is a queue | It is a replayable log; messages survive being read |
| Kafka guarantees ordering | Only **within one partition** |
| More consumers = more work completed per second | Capped by partition count |
| Exactly-once is available end-to-end | Only within Kafka; your database side effects are outside it |
| Idempotent producer makes consumers safe | It only dedups producer retries |
| A message is delivered once | At-least-once means duplicates are normal |
| `send()` returning means it is stored | It may still be in the producer buffer |
| Consuming deletes the message | Retention deletes it, on a timer |
| Adding partitions is harmless | It changes `hash % n` and breaks existing key→partition mapping |
| A DLT is a bin | It is unfinished work; alert on it |
| Auto-commit is convenient | It gives you loss *and* duplicates, depending on timing |

---

## Notes for a real running system

- **Use suitable replication settings**, such as `replication.factor=3`, `min.insync.replicas=2`, and `acks=all`, to keep copies and require enough in-sync replicas before accepting writes.
- **Alert on consumer lag** for each group and partition. Growing lag means work is falling behind.
- **Alert on messages in the DLT.** They represent work that did not complete.
- **Create topics with reviewed settings** rather than relying on automatic creation.
- **Choose enough partitions for expected growth.** Adding them later can change where an existing key goes.
- **Check message compatibility** using a schema registry or clearly reviewed JSON rules.
- **Watch rebalances.** Frequent partition reassignment may mean slow processing or unstable consumers.
- **Keep messages longer than the longest outage you plan to recover from.** Otherwise unread messages can expire.
- **Plan deletion needs before storing personal data in messages.** Long retention and replay make removal harder. Compacted topics can use tombstones, records marking a key for deletion, but this still needs careful handling.

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

Next, add Java code using Spring Kafka. You will send and read JSON messages with an envelope containing `messageId`, `type`, `occurredAt`, and `version`. You will also choose when offsets are saved, handle failed messages with retries and a DLT, and connect the duplicate check to `processed_messages`.

First events: `EventCreated` and `EventUpdated` — really one-way notifications, so we can learn the
supporting code before Phase 12 makes the booking flow depend on it.

**Pre-reading for Phase 10b:** none. Re-read your notes on partitions and keys instead.
