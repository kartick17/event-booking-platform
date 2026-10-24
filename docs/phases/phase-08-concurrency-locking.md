# Phase 8 — Concurrency: race conditions, locking, and the double-booking problem

**Time:** 5–6 hours
**Needs:** Phase 7 finished.
**Pre-reading:** database isolation levels; the lost-update problem.

This phase checks that seat counts stay correct **when several people book at the same time**. A method can work perfectly for one request and still give the wrong result when two requests overlap.

---

## In plain English

This phase prevents selling the same last seat to two people. The problem appears when requests overlap: both read the old count before either finishes updating it.

Example: only 1 seat remains. Two requests both read 1 and both decide to reserve it. Checking the number in Java is not enough; the database update must also protect against a competing change.

Words used in this phase:

- **Concurrency:** more than one operation is in progress at the same time.
- **Race condition:** the result changes depending on which overlapping operation happens first.
- **Critical section:** the part of the code that needs protection from competing changes.
- **Isolation level:** database rules about what one transaction can see of another transaction's work.
- **Optimistic lock:** check whether the row changed since it was read, using a version number.
- **Pessimistic lock:** take a database lock before changing the row; competing changes wait.
- **Atomic update:** one protected database operation that checks and changes the value together.
- **Distributed lock:** a lock shared by several application instances, often through another system such as Redis.
- **Deadlock:** two transactions each wait for a lock held by the other.

Start with the last-seat example. Then compare the three database approaches using the same test, as the build steps describe.

---

# PART A — Understand the ideas

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

**Each request made sense on its own.** Both saw 1 seat. The problem was the gap between reading that count and writing the new count. Another request can change the data during that gap.

This is called **read–modify–write**: read a value, calculate a new one in the application, then save it. Other work can happen between those steps:

```java
int seats = event.getAvailableSeats();   // READ
if (seats >= qty) {                       // decide on a value that may already be stale
    event.setAvailableSeats(seats - qty); // MODIFY
    repository.save(event);               // WRITE
}
```

The code between reading and writing is the **critical section**. We need to protect it or replace it with one database operation.

## A2. Why isolation levels do not save you by default

A transaction groups changes together, but does not automatically prevent every conflict with another transaction. Its isolation level and the update method matter.

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

**READ COMMITTED** is the usual default in Postgres, Oracle, and SQL Server. It prevents reading uncommitted data, but the read–modify–write code shown here can still lose an update: both requests may read `1` before either saves.

**MVCC (multi-version concurrency control)** lets Postgres keep different versions of rows. Ordinary reads can see a committed version without waiting for a writer. This helps performance, but a value read earlier may be out of date when the application later writes.

Postgres can reject the competing update at `REPEATABLE READ`, but the application must then handle that transaction failure. Here we compare methods that protect the particular seat row rather than changing the isolation level for all the work.

## A3. Optimistic locking

**Optimistic locking assumes collisions are uncommon.** Read normally, then check at update time whether someone else changed the row. If they did, reject this update.

A `version` column, checked on every update:

```sql
UPDATE events
   SET available_seats = 98, version = 6
 WHERE id = 1 AND version = 5;
```

If another transaction has already changed the version to 6, this update matches **zero rows**. Hibernate notices that nothing was updated and throws `OptimisticLockException`.

```
Thread A: read version=5        Thread B: read version=5
Thread A: UPDATE ... version=5 → 1 row  ✓  (version now 6)
Thread B: UPDATE ... version=5 → 0 rows ✗  OptimisticLockException
```

**This already works in our project.** `@Version` went into the `Event` entity in Phase 4, and Phase 6's
Drill 5 saw it fire without us naming it.

**Useful when:** competing changes are uncommon and transactions are short.
**Less useful when:** many requests keep changing the same row, because failures and retries add more work.

**Handle the conflict.** Do not let an expected `OptimisticLockException` become an unexplained 500 response. Decide whether to return a conflict or retry safely.

## A4. Pessimistic locking

**Pessimistic locking assumes collisions are likely.** Lock the row first. Other transactions needing a conflicting lock must wait.

```sql
SELECT * FROM events WHERE id = 1 FOR UPDATE;
```

The row stays locked until the transaction commits or rolls back. Another `FOR UPDATE` on the same row waits for that lock to be released.

```
Thread A: SELECT ... FOR UPDATE  → gets the lock
Thread B: SELECT ... FOR UPDATE  → BLOCKS, waiting
Thread A: UPDATE, COMMIT         → lock released
Thread B: (unblocks) reads the NEW value → decides correctly
```

Correct, and easy to reason about. The costs are real:

- **Less parallel work:** reservations for the same popular event must wait their turn.
- **Deadlocks:** A holds row 1 and wants row 2, while B holds row 2 and wants row 1. Postgres detects the cycle and stops one transaction. Take locks in the same order, such as increasing id, to reduce this risk.
- **Long waits:** making an HTTP call while holding the lock makes other bookings wait for the network too. Keep the locked transaction short.
- **Timeouts:** set a waiting limit so one stuck transaction cannot hold everyone indefinitely.

`PESSIMISTIC_WRITE` is an exclusive lock (nobody else reads-for-update or writes). `PESSIMISTIC_READ`
is shared (others may read, nobody may write).

## A5. The atomic update — usually the best answer

The earlier methods protect separate read and write steps. **An atomic update combines the condition and change into one SQL statement:**

```sql
UPDATE events
   SET available_seats = available_seats - :quantity,
       version = version + 1
 WHERE id = :id
   AND available_seats >= :quantity
   AND status = 'PUBLISHED';
```

**The database checks and updates together.** It subtracts from the current saved value and protects the row while doing so. The calculation does not use an older value previously read into Java.

Check how many rows the statement changed:
- **1 row:** the reservation succeeded.
- **0 rows:** the conditions did not match, such as too few seats or an unpublished event. No seats were changed.

| | Optimistic | Pessimistic | Atomic UPDATE |
|---|---|---|---|
| Blocking | none | yes | microseconds |
| Retries needed | yes | no | no |
| Deadlock risk | none | yes | none |
| Work completed per second under requests competing for the same resource | poor | poor | **best** |
| Works for complex multi-field logic | yes | yes | **no** |
| Business rules in Java | yes | yes | **no — they move into SQL** |

**The cost:** the seat rule now also appears in SQL: `available_seats >= quantity AND status = 'PUBLISHED'`. Keep it consistent with the rule expressed by `Event.reserveSeats()` from Phase 4.

**Our approach:** keep the Event object's rules clear, and use the atomic SQL update to protect this frequently changed count. Implement all three approaches in the existing steps so you can compare their behaviour.

## A6. Distributed locks, and why we do not need one here

A database can protect its own row. But a task such as updating a shared file or running one scheduled job across several instances may have no row to lock. Then the instances may need a lock they can all see.

The Redis version:

```
SET lock:event:1 <random-token> NX PX 5000
```

- `NX` creates the key only if it does not already exist. Redis checks and sets it as one operation.
- `PX 5000` expires the key after 5 seconds. Otherwise a crashed lock holder could leave the resource locked indefinitely.
- The random token identifies the owner. Release must check the token and delete the key in one Lua script. Separate operations could accidentally delete a lock that expired and was acquired by someone else.

**Redlock** is an approach using several Redis nodes. Its safety under long pauses and clock differences has been debated, including by Martin Kleppmann and Redis creator Salvatore Sanfilippo. The important lesson is to understand a lock's assumptions before relying on it for correctness.

**A timed lock can expire while the old holder is still working.** A **fencing token** helps detect this: every new lock gets a larger number, and the protected resource rejects work carrying an older number. The resource must actually check the token for this to help.

**Seat counts already have a shared database row.** Use the database's protection for that row. Adding Redis locking in front would add another dependency and new failure cases without replacing the need for correct database updates.

Choose a distributed lock only when the resource and the required protection actually need one. Being able to explain that choice matters more than adding a lock everywhere.

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

The `sleep` makes the overlap easier to reproduce. The gap also exists without a sleep, but you may need more attempts to observe it on a fast computer.

Set up an event with exactly 5 seats and fire 20 happening at the same time reservations:

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

**A non-negative check cannot detect this lost update.** Two requests read `5` and both write `4`. Each saved value passes `CHECK (available_seats >= 0)`, but two reservations reduced the count only once. A constraint checks the value being saved, not whether the application calculated it from a fresh read.

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

**Each retry needs a new transaction and fresh read.** Put `@Retryable` outside `@Transactional` in the wrapper order. Retrying inside the same transaction can reuse the failed transaction or old state. Spring Retry normally provides the needed order; check it if every attempt repeats the same failure.

**Retry is safe for this rejected optimistic update because it did not commit a change.** The next attempt gets fresh data. That is different from retrying an HTTP write after a timeout, where the remote change may already have committed.

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

**Set a lock waiting limit.** Otherwise a stuck transaction can make every competing reservation wait without an end. The example allows 3 seconds, then returns an error. Postgres also provides a session-level `lock_timeout`.

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

**The flags keep Java's tracked entities and the SQL update from disagreeing.** `flushAutomatically = true` sends pending entity changes to the database before the query. `clearAutomatically = true` clears tracked entities afterwards so the next `getById` does not reuse an old in-memory seat count.

**Keep both versions of the seat rule in sync.** The SQL condition `available_seats >= :quantity AND status = 'PUBLISHED'` also appears in `Event.reserveSeats()`. Java compilation does not check the SQL rule for you. We accept the extra maintenance for this frequently updated count and document the connection.

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
requests competing for the same resource the gap widens.

**Run each three times.** Optimistic locking's timing varies most, because it depends on how many
retries happen — which is exactly its weakness under requests competing for the same resource.

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

**Use one `setIfAbsent(key, token, ttl)` command**, equivalent to `SET key val NX PX ttl`. Separate `EXISTS` and `SET` commands leave a gap where two callers could both claim the same lock.

**The TTL is mandatory.** If the holder crashes, the lock must expire on its own. **A lock with no TTL
is a deadlock waiting for a crash.**

**The random token identifies the owner.** Without it:

```
A acquires. A pauses (GC). Lock expires. B acquires.
A resumes, calls DEL → deletes B's lock. Two holders. Total failure.
```

**Release with one Lua script.** Checking the owner token and deleting the lock must happen together. If they are separate, the lock could expire and be acquired by someone else between the check and delete.

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

**If work lasts longer than the TTL, the lock can expire while that work is still running.** A different process may then acquire it. A long Java memory-cleanup pause can cause this. A fencing token checked by the protected resource can reject old-owner work; a timed lock alone cannot.

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

Set `maxAttempts = 2` on the optimistic version and run 100 happening at the same time reservations. Many requests fail
with a conflict even though seats were available — they simply lost three races in a row.

**This is optimistic locking's honest weakness:** under high requests competing for the same resource it does not degrade in a controlled way,
it *fails* gracefully-ish. The atomic update has no such cliff, and comparing the two under the same
load is the most persuasive argument for it.

### Drill 5 — the missing `clearAutomatically`

Remove `clearAutomatically = true` from the atomic query and call reserve twice in one transaction. The
second call reads a **out of date** `available_seats` from the persistence context — Hibernate's first-level
cache still holds the pre-update object.

**A native query bypasses the persistence context.** Your entity and your database now disagree, with no
error. Really nasty, and now recognisable.

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
| `@Retryable` inside the transaction | Retries re-read the same out of date snapshot | Retry outside, fresh transaction each time |
| Pessimistic lock with no timeout | Requests hang forever | `jakarta.persistence.lock.timeout` |
| Locks taken in different orders | Deadlocks | Consistent order, always |
| Remote call while holding a lock | Everyone blocked for the call duration | Never |
| Distributed lock without TTL | A crash locks the resource forever | Always a TTL |
| Deleting a lock without checking the token | You delete someone else's lock | Random token + Lua compare-and-delete |
| Native `@Modifying` without `clearAutomatically` | Out of date entities in the same transaction | Add both flags |
| Distributed lock in front of a database row | Slower, extra dependency, new failure modes | Let the database do it |

---

## Notes for a real running system

- **Test overlapping requests under load** using `k6`, `wrk`, or JMeter before launch. One request at a time will not expose these timing problems.
- **Monitor optimistic-lock retry rates.** More retries mean more requests are competing for the same data.
- **Find frequently updated rows.** At very large scale, a seat counter may be split into smaller counters, but correct totals and reservations then become more complex. Measure before making that change.
- **Plan reservation expiry** if seats are held while a user pays. A `reservations` table with `expires_at` and a cleanup job can record those holds; a count alone cannot. A hold time such as 10 minutes is a product decision.
- **Use idempotency keys from Phase 9** to handle repeated clicks for the same booking.
- **Log seat changes with a correlation id** so unexpected counts can be investigated.
- **Run a comparison job** between `capacity - sum(bookings)` and `available_seats`, using the correct booking quantities and statuses. Investigate differences rather than waiting for a user to report them.

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

**Pre-reading for Phase 9:** HTTP method meaning and behaviour — which methods are idempotent by definition, and why
POST is not.
