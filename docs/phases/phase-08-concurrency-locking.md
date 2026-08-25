# Phase 8 — Concurrency: race conditions, locking, and the double-booking problem

**Time:** 5–6 hours
**Needs:** Phase 7 finished.
**Pre-reading:** database isolation levels; the lost-update problem.

This phase is where a booking system is won or lost. Everything so far has been about structure.
This is about **being correct when two people click at the same millisecond**.

---

# PART A — Concepts

## A1. The race condition, drawn out

Two users, one seat left.

```
time    Thread A (user 1)              Thread B (user 2)
────────────────────────────────────────────────────────────────
t1      SELECT available_seats → 1
t2                                     SELECT available_seats → 1
t3      check: 1 >= 1  ✓
t4                                     check: 1 >= 1  ✓
t5      UPDATE SET available = 0
t6                                     UPDATE SET available = 0
t7      INSERT booking (1 seat)
t8                                     INSERT booking (1 seat)
────────────────────────────────────────────────────────────────
        Result: 2 bookings. 1 seat. available_seats = 0.
```

**Both threads were correct in isolation.** Both read a true value, both made a valid decision. The bug
is in the *gap* between reading and writing — the window where the value can change underneath you.

This shape is called **read–modify–write**, and it is the source of most concurrency bugs:

```java
int seats = event.getAvailableSeats();   // READ
if (seats >= qty) {                       // decide on a value that may already be stale
    event.setAvailableSeats(seats - qty); // MODIFY
    repository.save(event);               // WRITE
}
```

The **critical section** is everything between the read and the write. Making that section safe is the
whole job.

## A2. Why isolation levels do not save you by default

You would hope a transaction fixes it. Mostly, it does not.

| Level | Dirty read | Non-repeatable read | Phantom | Lost update |
|---|---|---|---|---|
| READ UNCOMMITTED | possible | possible | possible | possible |
| **READ COMMITTED** ← Postgres default | no | possible | possible | **possible** |
| REPEATABLE READ | no | no | Postgres: no | **prevented in Postgres** (error) |
| SERIALIZABLE | no | no | no | prevented (error) |

The anomalies in plain words:

- **Dirty read** — you read a change another transaction has not committed. It may roll back and you
  acted on data that never existed.
- **Non-repeatable read** — you read the same row twice in one transaction and get different values.
- **Phantom read** — you run the same query twice and new rows appear.
- **Lost update** — two transactions read, both modify, and the second overwrites the first. **This is
  our bug.**

**READ COMMITTED — the default in Postgres, Oracle and SQL Server — does not prevent lost updates.**
Each `SELECT` sees a fresh, committed snapshot, which is exactly why both threads read `1`.

**Postgres uses MVCC** (multi-version concurrency control): writers create new row versions instead of
blocking readers. Excellent for throughput, and it means **readers are never blocked, so nothing warns
you that your read is about to go stale**.

Raising the level to `REPEATABLE READ` does work in Postgres — the second transaction fails with a
serialization error. But it applies to the whole transaction, costs throughput, and forces you to handle
retries anyway. **Better to solve it precisely, at the row.**

## A3. Optimistic locking

**Assume conflicts are rare.** Do not lock. Detect the conflict at write time and fail.

A `version` column, checked on every update:

```sql
UPDATE events
   SET available_seats = 98, version = 6
 WHERE id = 1 AND version = 5;
```

If another transaction already bumped the version to 6, this matches **zero rows**. Hibernate counts the
affected rows, sees 0, and throws `OptimisticLockException`.

```
Thread A: read version=5        Thread B: read version=5
Thread A: UPDATE ... version=5 → 1 row  ✓  (version now 6)
Thread B: UPDATE ... version=5 → 0 rows ✗  OptimisticLockException
```

**This already works in our project.** `@Version` went into the `Event` entity in Phase 4, and Phase 6's
Drill 5 saw it fire without us naming it.

**Right when:** conflicts are rare, transactions are short, you can retry.
**Wrong when:** conflicts are common — you spend all your time retrying, and under heavy contention it
degrades badly.

**Non-negotiable:** if you use optimistic locking, **you must handle the failure**. An unhandled
`OptimisticLockException` reaching the user as a 500 is a bug, not a design.

## A4. Pessimistic locking

**Assume conflicts are likely.** Take the lock first; everyone else waits.

```sql
SELECT * FROM events WHERE id = 1 FOR UPDATE;
```

The row is locked until the transaction ends. Other transactions attempting `FOR UPDATE` on that row
**block**.

```
Thread A: SELECT ... FOR UPDATE  → gets the lock
Thread B: SELECT ... FOR UPDATE  → BLOCKS, waiting
Thread A: UPDATE, COMMIT         → lock released
Thread B: (unblocks) reads the NEW value → decides correctly
```

Correct, and easy to reason about. The costs are real:

- **Throughput.** Reservations on one hot event serialise completely.
- **Deadlocks.** A locks row 1 then row 2; B locks row 2 then row 1. Both wait forever. Postgres detects
  it and kills one with a deadlock error. **Prevention: always take locks in a consistent order** (for
  example, ascending id).
- **Holding a lock across a network call is a disaster.** A lock held for a 2-second Feign call blocks
  every other booking for 2 seconds. Phase 6 said never hold a transaction across a remote call; with
  a lock it is far worse.
- **You must set a timeout.** Otherwise a stuck transaction blocks everyone indefinitely.

`PESSIMISTIC_WRITE` is an exclusive lock (nobody else reads-for-update or writes). `PESSIMISTIC_READ`
is shared (others may read, nobody may write).

## A5. The atomic update — usually the best answer

Both approaches above are read–modify–write with a guard. **The third option removes the gap
entirely:**

```sql
UPDATE events
   SET available_seats = available_seats - :quantity,
       version = version + 1
 WHERE id = :id
   AND available_seats >= :quantity
   AND status = 'PUBLISHED';
```

**One statement. The database reads and writes atomically.** The row is locked internally for the
duration of the statement — microseconds — and `available_seats - :quantity` is computed from the
*current* value, not one your application read earlier.

Check the affected row count:
- **1 row** → reserved.
- **0 rows** → not enough seats, or not published. No exception, no retry, no lock held.

| | Optimistic | Pessimistic | Atomic UPDATE |
|---|---|---|---|
| Blocking | none | yes | microseconds |
| Retries needed | yes | no | no |
| Deadlock risk | none | yes | none |
| Throughput under contention | poor | poor | **best** |
| Works for complex multi-field logic | yes | yes | **no** |
| Business rules in Java | yes | yes | **no — they move into SQL** |

**The trade is real and worth stating:** the atomic update is fastest and simplest, but the rule
(`available_seats >= quantity AND status = 'PUBLISHED'`) now lives in SQL rather than in your aggregate.
That is a genuine loss — it is the invariant we deliberately put inside `Event.reserveSeats()` in
Phase 4.

**Our decision:** keep the aggregate as the place the rules are *expressed*, and use the atomic update
as the enforcement mechanism for the one hot field. We will implement all three so you can measure
them, then choose.

## A6. Distributed locks, and why we do not need one here

Everything above relies on **one database row** being the single point of truth. When the resource is
not in a database — a file, an external API, a scheduled job that must run on exactly one instance —
there is no row to lock, and you need a lock that all instances can see.

The Redis version:

```
SET lock:event:1 <random-token> NX PX 5000
```

- `NX` — set only if it does not exist. Atomic, because Redis is single-threaded (Phase 5, concept A5).
- `PX 5000` — expire after 5 seconds. **Without a TTL, a crashed holder locks the resource forever.**
- The random token identifies the owner, so you only delete *your own* lock — and the delete must be a
  Lua script that compares and deletes atomically, or you can delete a lock that already expired and was
  taken by someone else.

**Redlock** is the multi-node algorithm. It is genuinely contested: Martin Kleppmann's critique argues
it is unsafe under GC pauses and clock drift; Salvatore Sanfilippo (Redis's author) disagrees. **Know
that the debate exists** — mentioning it is a strong senior signal.

**The fundamental limit:** a lock with a TTL can expire while you still believe you hold it — a long GC
pause is enough. The safe pattern is a **fencing token**: an ever-increasing number handed out with the
lock, which the protected resource checks and rejects if it is lower than one it has already seen.

**For seat reservation, all of this is unnecessary and worse.** The database row *is* the shared
resource, and the database already provides atomicity. **A distributed lock in front of a database row
is slower, adds a dependency, and introduces failure modes the database does not have.**

Being able to say "we did not need a distributed lock here, and here is why" is worth more than being
able to implement one.

---

# PART B — Build it

## Step 1 — Prove the bug exists

Do not take my word for it. Reproduce it.

**Temporarily** replace event-service's `reserveSeats` with the naive version, and remove `@Version`
from the `Event` entity (comment out the field and its column mapping):

```java
    @Transactional
    public Event reserveSeatsNaive(Long eventId, int quantity) {
        Event event = getById(eventId);

        // widen the race window so it is reliably visible
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        event.reserveSeats(quantity);
        return event;
    }
```

The `sleep` is not cheating — it makes a real race reproducible instead of occasional. The window exists
without it; on a fast machine you would just have to try a hundred times.

Set up an event with exactly 5 seats and fire 20 concurrent reservations:

```bash
docker exec -it ebp-postgres psql -U ebp -d event_db \
  -c "UPDATE events SET available_seats = 5, capacity = 100 WHERE id = 1;"

for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{http_code} " -X POST \
    "http://localhost:8082/api/v1/events/1/reserve?quantity=1" \
    -H "X-User-Id: test@x.com" -H "X-User-Roles: ROLE_USER" &
done; wait; echo

docker exec -it ebp-postgres psql -U ebp -d event_db \
  -c "SELECT available_seats FROM events WHERE id = 1;"
```

**Expected result: far more than 5 succeed, and `available_seats` is negative or nonsensical.**

```
201 201 201 201 201 201 201 201 201 201 201 409 409 ...
 available_seats
-----------------
              -7
```

**Negative seats.** Seventeen people hold tickets for five chairs.

**And notice what did not save you:** the `CHECK (available_seats >= 0)` constraint from Phase 4 *should*
have. It did not, because each transaction's own view satisfied it — Hibernate wrote `4`, then another
wrote `4`, each starting from a stale read of `5`. **Constraints validate the value being written, not
the arithmetic that produced it.**

**This is a real production bug in a real booking system**, and you just created it in ten seconds.
Restore `@Version` and the proper method before continuing.

## Step 2 — Optimistic locking, handled properly

With `@Version` restored, run the same test. Now you get a mix of `201`, `409`, and some **`500`s** —
`OptimisticLockException` escaping as an internal error. Correct data, terrible API.

**Handle it.** Add to event-service's `GlobalExceptionHandler`:

```java
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class,
                       OptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> handleOptimisticLock(Exception ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(ApiError.of(409, "Conflict",
                        "This event was updated by someone else. Please try again.",
                        request.getRequestURI()));
    }
```

Spring translates Hibernate's `OptimisticLockException` into
`ObjectOptimisticLockingFailureException`, so catch the Spring type.

**409 with `Retry-After`, not 500.** Nothing is broken — two people collided. The client is being told
"try again", which is actionable, unlike "internal server error".

**Now retry it server-side**, so the user does not see a conflict at all for something we can resolve
ourselves. Add `spring-retry` and `starter-aop` to event-service:

```java
    @Retryable(
            retryFor = ObjectOptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2, random = true)
    )
    @Transactional
    public Event reserveSeatsOptimistic(Long eventId, int quantity) {
        Event event = getById(eventId);
        event.reserveSeats(quantity);
        return event;
    }
```

**Three details that are easy to get wrong:**

**`@Retryable` must sit *outside* `@Transactional`** in the proxy order, so each attempt gets a **fresh
transaction and a fresh read**. Retrying inside the same transaction re-reads the same stale snapshot
and fails identically forever. Spring Retry's aspect has a lower order by default, so this works — but
if you ever see a retry loop that always fails the same way, this ordering is the first thing to check.

**Retry is safe here** because this operation is *conditionally* idempotent in the useful sense: the
failed attempt changed nothing (its update matched zero rows), so re-running it is not a duplicate.
Contrast with Phase 7's drill, where the write **had** committed and only the response was lost. **The
difference between "definitely did not happen" and "might have happened" is what decides whether retry
is safe.**

**`random = true`** — jitter, for the same reason as Phase 7.

Rerun the 20-request test: mostly `201` and `409`, **no 500s, and `available_seats` never goes below
0**.

## Step 3 — Pessimistic locking, for comparison

**File:** `.../infrastructure/EventRepository.java`

```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);
```

**File:** `.../application/EventService.java`

```java
    @Transactional
    public Event reserveSeatsPessimistic(Long eventId, int quantity) {
        Event event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));

        event.reserveSeats(quantity);
        return event;
    }
```

**`@Lock(PESSIMISTIC_WRITE)` makes Hibernate emit `SELECT ... FOR UPDATE`.** Turn on
`spring.jpa.properties.hibernate.format_sql: true` and `logging.level.org.hibernate.SQL: DEBUG` and read
it yourself — seeing `for update` in the log is worth more than being told about it.

**The lock timeout is the line people forget.** Without it, a transaction that hangs blocks every other
reservation for that event indefinitely. Three seconds, then fail with a clear error. In Postgres you
can also set `lock_timeout` at the session level.

**Two rules that come with pessimistic locking, and both are absolute:**

1. **Never make a remote call while holding the lock.** The lock is held until commit; a 2-second Feign
   call means 2 seconds of blocking for everyone. Phase 6's rule, with teeth.
2. **Always take multiple locks in a consistent order** (ascending id). This is the only reliable
   deadlock prevention.

## Step 4 — The atomic update

**File:** `.../infrastructure/EventRepository.java`

```java
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE events
               SET available_seats = available_seats - :quantity,
                   version = version + 1,
                   updated_at = NOW()
             WHERE id = :id
               AND status = 'PUBLISHED'
               AND available_seats >= :quantity
            """, nativeQuery = true)
    int reserveSeatsAtomically(@Param("id") Long id, @Param("quantity") int quantity);
```

**File:** `.../application/EventService.java`

```java
    @Transactional
    public Event reserveSeatsAtomic(Long eventId, int quantity) {
        int rowsAffected = eventRepository.reserveSeatsAtomically(eventId, quantity);

        if (rowsAffected == 0) {
            Event event = getById(eventId);          // only now, to build a good error
            if (!event.getStatus().acceptsBookings()) {
                throw new InvalidEventStateException("Event is not open for booking");
            }
            throw new InsufficientSeatsException(eventId, quantity, event.getAvailableSeats());
        }

        return getById(eventId);
    }
```

**Why this is the fastest correct option:**

- **No read before the write.** The gap from concept A1 does not exist.
- **No blocking.** The row lock lasts for the duration of one statement.
- **No retries.** There is nothing to retry — either the `WHERE` matched or it did not.
- The `WHERE available_seats >= :quantity` clause is evaluated by the database **against the live row**,
  under its own internal lock.

**The two annotation flags matter.** `flushAutomatically = true` pushes pending changes to the database
before this native query runs, so it does not operate on stale state. `clearAutomatically = true` clears
the persistence context afterwards — otherwise Phase 2b's warning applies: entities already loaded keep
their old `available_seats`, and the `getById` on the next line would return a **cached, stale object**.

**The cost, stated honestly:** `available_seats >= :quantity AND status = 'PUBLISHED'` now lives in SQL,
duplicating the rules in `Event.reserveSeats()`. Two places to change, and a native query that no
compiler checks. **We accept it for this one hot field** because a booking platform that oversells is
worthless, and a comment on the entity method points at the SQL. **Naming the trade-off is the skill;
pretending there is none is not.**

## Step 5 — Measure all three

Point the reserve endpoint at each implementation in turn and run the same load:

```bash
reset_seats() {
  docker exec -it ebp-postgres psql -U ebp -d event_db \
    -c "UPDATE events SET available_seats = 50, version = 0 WHERE id = 1;" > /dev/null
}

run_test() {
  reset_seats
  start=$(date +%s%N)
  for i in $(seq 1 100); do
    curl -s -o /dev/null -w "%{http_code}\n" -X POST \
      "http://localhost:8082/api/v1/events/1/reserve?quantity=1" \
      -H "X-User-Id: test@x.com" -H "X-User-Roles: ROLE_USER" &
  done | sort | uniq -c
  wait
  end=$(date +%s%N)
  echo "took $(( (end - start) / 1000000 )) ms"
  docker exec -it ebp-postgres psql -U ebp -d event_db \
    -c "SELECT available_seats FROM events WHERE id = 1;"
}

run_test
```

Typical shape of the results (your numbers will differ; the *ordering* is what matters):

| Approach | 201 | 409 | Time | Final seats |
|---|---|---|---|---|
| Optimistic + retry | 50 | 50 | ~2500 ms | 0 |
| Pessimistic | 50 | 50 | ~1800 ms | 0 |
| Atomic UPDATE | 50 | 50 | ~600 ms | 0 |

**All three are correct — exactly 50 succeed and seats land on 0.** That is the point: correctness is
the baseline, and then you choose on cost.

**The atomic update is several times faster** because it neither blocks nor retries. Under real
contention the gap widens.

**Run each three times.** Optimistic locking's timing varies most, because it depends on how many
retries happen — which is exactly its weakness under contention.

## Step 6 — A distributed lock, for the cases that need one

Not needed for seats (concept A6). Build it anyway, once, so you understand what it is and can say why
you did not use it.

**File:** `.../infrastructure/RedisDistributedLock.java`

```java
package com.eventbooking.event.infrastructure;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class RedisDistributedLock {

    private static final String RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> releaseScript;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
        this.releaseScript = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
    }

    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public boolean release(String key, String token) {
        Long released = redis.execute(releaseScript, List.of(key), token);
        return Long.valueOf(1).equals(released);
    }
}
```

**Every line here is defending against a specific failure:**

**`setIfAbsent(key, token, ttl)` is one Redis command** (`SET key val NX PX ttl`), and Redis is
single-threaded, so it is atomic. Doing it as `EXISTS` then `SET` would be a race — the exact bug this
class exists to prevent.

**The TTL is mandatory.** If the holder crashes, the lock must expire on its own. **A lock with no TTL
is a deadlock waiting for a crash.**

**The random token identifies the owner.** Without it:

```
A acquires. A pauses (GC). Lock expires. B acquires.
A resumes, calls DEL → deletes B's lock. Two holders. Total failure.
```

**The release must be a Lua script**, because "check the token, then delete" is two operations, and the
lock can expire between them. Redis runs a script atomically — Lua is not decoration here, it is the
only correct way to do a compare-and-delete.

**Usage, with the guarantee it cannot give you:**

```java
String token = lock.tryAcquire("lock:event:" + eventId, Duration.ofSeconds(5));
if (token == null) {
    throw new ResourceBusyException("Another operation is in progress");
}
try {
    // critical section — MUST finish well inside the 5 second TTL
} finally {
    lock.release("lock:event:" + eventId, token);
}
```

**Read that comment again.** If your critical section outruns the TTL, the lock expires while you are
still working and someone else acquires it. **The lock does not stop you, and there is no way for it
to.** A long GC pause is enough. The real fix is a **fencing token** the protected resource checks
(concept A6) — which is why "just use a distributed lock" is not the simple answer people assume.

**Legitimate uses:** ensuring a scheduled job runs on one instance only; serialising access to a
non-transactional external API; preventing a duplicate expensive computation (Phase 5's cache
stampede).

**Not for seats.** The database does this better.

---

## Break it on purpose

### Drill 1 — the oversell (do this first)

Step 1 above. Do not skip it. **Seeing `available_seats = -7` with your own eyes is the entire
foundation of this phase.**

### Drill 2 — force a deadlock

Two transactions locking two events in opposite orders:

```sql
-- session 1
BEGIN;
SELECT * FROM events WHERE id = 1 FOR UPDATE;
-- now run session 2's first two lines, then come back
SELECT * FROM events WHERE id = 2 FOR UPDATE;

-- session 2
BEGIN;
SELECT * FROM events WHERE id = 2 FOR UPDATE;
SELECT * FROM events WHERE id = 1 FOR UPDATE;
```

Postgres detects the cycle and kills one:

```
ERROR: deadlock detected
DETAIL: Process 123 waits for ShareLock on transaction 456; blocked by process 789.
```

**Now fix it by ordering:** make both sessions lock id 1 before id 2. No deadlock, ever. **Consistent
lock ordering is the whole technique** — and it is the answer to a very common interview question.

### Drill 3 — a lock held too long

Add a `Thread.sleep(10000)` inside `reserveSeatsPessimistic`, after acquiring the lock. Fire two
reservations for the same event.

The second waits, then fails after 3 seconds with a lock timeout. **The lock timeout you set in step 3
just saved you from an indefinite hang.**

Now imagine that sleep is a Feign call to a slow service. **That is why you never make a remote call
while holding a lock.**

### Drill 4 — retry exhaustion

Set `maxAttempts = 2` on the optimistic version and run 100 concurrent reservations. Many requests fail
with a conflict even though seats were available — they simply lost three races in a row.

**This is optimistic locking's honest weakness:** under high contention it does not degrade gracefully,
it *fails* gracefully-ish. The atomic update has no such cliff, and comparing the two under the same
load is the most persuasive argument for it.

### Drill 5 — the missing `clearAutomatically`

Remove `clearAutomatically = true` from the atomic query and call reserve twice in one transaction. The
second call reads a **stale** `available_seats` from the persistence context — Hibernate's first-level
cache still holds the pre-update object.

**A native query bypasses the persistence context.** Your entity and your database now disagree, with no
error. Genuinely nasty, and now recognisable.

### Drill 6 — the double-click

The realistic version of this bug. One user, one fast double-click:

```bash
curl -X POST http://localhost:8080/api/v1/bookings -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" -d '{"eventId":1,"quantity":1}' &
curl -X POST http://localhost:8080/api/v1/bookings -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" -d '{"eventId":1,"quantity":1}' &
wait
```

**Two bookings. Two seats. One intent.**

Every lock in this phase worked perfectly — both requests were legitimate, sequential, and correctly
processed. **Locking solves "two users, one seat". It cannot solve "one user, one intent, two
requests."**

That needs **idempotency**, and it is Phase 9.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Read-modify-write with no guard | Oversold inventory, negative counts | Version, lock, or atomic update |
| Relying on the default isolation level | Lost updates | READ COMMITTED does not prevent them |
| Relying on a `CHECK` constraint | Negative values still appear | Constraints check values, not arithmetic |
| `@Version` present but exception unhandled | 500s under load | Map to 409, or retry |
| `@Retryable` inside the transaction | Retries re-read the same stale snapshot | Retry outside, fresh transaction each time |
| Pessimistic lock with no timeout | Requests hang forever | `jakarta.persistence.lock.timeout` |
| Locks taken in different orders | Deadlocks | Consistent order, always |
| Remote call while holding a lock | Everyone blocked for the call duration | Never |
| Distributed lock without TTL | A crash locks the resource forever | Always a TTL |
| Deleting a lock without checking the token | You delete someone else's lock | Random token + Lua compare-and-delete |
| Native `@Modifying` without `clearAutomatically` | Stale entities in the same transaction | Add both flags |
| Distributed lock in front of a database row | Slower, extra dependency, new failure modes | Let the database do it |

---

## Production considerations

- **Load-test the hot path.** Concurrency bugs are invisible at one request per second and obvious at a
  thousand. `k6`, `wrk` or JMeter, before launch, not after.
- **Alert on optimistic-lock retry rates.** A rising rate means contention is growing and you may need
  to change approach.
- **Watch for hot rows.** One popular event serialises everything. At extreme scale you shard the
  counter (ten rows of 50 seats instead of one row of 500) and accept a more complex read.
- **Reservation expiry:** real ticketing holds seats for ~10 minutes while the user pays, then releases
  them. That needs a `reservations` table with `expires_at` and a cleanup job — not just a counter.
- **Idempotency keys** (Phase 9) for the double-click.
- **Log every seat movement** with a correlation id. When counts disagree, the log is the only way to
  find out why.
- **Reconciliation job:** periodically compare `capacity - sum(bookings)` against `available_seats`.
  Distributed systems drift; you want to find out before a customer does.

---

## Phase 8 checklist

- [ ] You reproduced the oversell and saw negative `available_seats`
- [ ] You understand why the `CHECK` constraint did not prevent it
- [ ] `@Version` produces `OptimisticLockException`, mapped to 409 with `Retry-After`
- [ ] `@Retryable` resolves conflicts without the user seeing them
- [ ] You saw `for update` in the Hibernate SQL log
- [ ] You caused a deadlock and fixed it with consistent lock ordering
- [ ] A pessimistic lock timed out instead of hanging forever
- [ ] The atomic UPDATE was measurably fastest, with all three correct
- [ ] You built a Redis lock and can explain why seats do not need one
- [ ] You can explain TTL, the random token, and why release must be a Lua script
- [ ] You reproduced the double-click and understand why no lock can fix it

---

## What Phase 9 does next

Drill 6 is unfinished business. One user, one intent, two requests — and every safety mechanism you have
correctly created two bookings.

Next: **idempotency.** Idempotency keys, the `idempotency_keys` table, what "safe to retry" really
means, and why this is the missing piece that makes Phase 7's retries safe on writes and Phase 12's
Kafka consumers correct.

**Pre-reading for Phase 9:** HTTP method semantics — which methods are idempotent by definition, and why
POST is not.
