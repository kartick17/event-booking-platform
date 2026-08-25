# Phase 7 — Resilience4j: timeouts, retry, circuit breaker, bulkhead, fallback

**Time:** 5–6 hours
**Needs:** Phase 6 finished (booking calling event over Feign).
**Pre-reading:** cascading failure; the thread-pool-exhaustion story behind circuit breakers.

---

# PART A — Concepts

## A1. How one slow service kills a whole platform

This story is why every pattern in this phase exists. Follow it slowly.

```
Normal:
  event-service responds in 20 ms
  booking-service: 200 Tomcat threads, each busy for ~30 ms
  → ~6000 requests/second, comfortable

event-service gets slow (a bad query, GC pause, a full disk):
  event-service now responds in 5 s
  booking-service: each thread now busy for 5 s
  → 200 threads / 5 s = 40 requests/second capacity

Traffic is still 500 requests/second:
  t+0s   200 threads busy waiting, 300 requests queue
  t+5s   queue is 3000 long, threads still waiting
  t+10s  Tomcat's accept queue is full, new connections refused
  t+15s  booking-service health check times out → Eureka marks it DOWN
  t+20s  gateway's threads are now waiting on booking → gateway degrades
  t+30s  the whole platform is down
```

**Nothing crashed.** Every service is "up". `event-service` got slower, and slowness propagated
backwards through everyone waiting on it.

**The key insight: waiting is the failure mode.** A dependency that fails *instantly* is survivable —
you return an error and free the thread. A dependency that takes 5 seconds consumes the one resource
you cannot make more of.

**Every pattern below is a way of not waiting.**

## A2. Timeout — the foundation

Already set in Phase 6, and worth restating as a principle: **an unbounded wait is an unbounded
failure.** Every network call gets a timeout. Every one.

**Choose it from your own budget, not from their average.** If your SLA is 2 seconds and you make two
downstream calls, each gets under a second. A downstream timeout longer than your own response budget is
meaningless — you have already failed by the time it fires.

**Resilience4j's `TimeLimiter`** exists on top of client timeouts because not every call is HTTP. It
needs the call to run in a `CompletableFuture` so it can be cancelled from outside. For Feign, the
client-level timeouts from Phase 6 are simpler and enough.

## A3. Retry — useful and dangerous in equal measure

Networks drop packets. A pod restarts. A brief GC pause. Many failures are **transient** — the same
request a moment later succeeds.

### When retrying is wrong

**1. Non-idempotent operations.** This is the big one.

```
POST /events/1/reserve?quantity=2
  → event-service reserves the seats, commits
  → the response is lost (timeout)
  → we retry
  → event-service reserves 2 MORE seats
```

**Four seats gone for a two-seat booking.** Phase 6, concept A4: a timeout does not tell you whether the
operation happened. **Retry is only safe when repeating the call is harmless** — which means `GET`,
`PUT`, `DELETE` by nature, and `POST` only when you have made it idempotent (Phase 9).

**2. 4xx errors.** The request is wrong. Retrying sends the identical wrong request.

**3. When the service is genuinely down.** Retrying three times against a dead service triples the load
on something already struggling and triples your own latency before failing anyway. That is what the
circuit breaker is for.

### The retry storm

The failure mode people forget:

```
event-service hiccups for 1 second
  1000 in-flight requests all fail
  all 1000 retry immediately
  → event-service now receives 2000 requests at once
  → it falls over properly this time
  → 2000 more retries
```

**Your retry policy turned a one-second hiccup into an outage.** The fixes:

**Exponential backoff** — wait 100 ms, then 200 ms, then 400 ms. Gives the dependency room to recover.

**Jitter** — randomise each wait. Without it, all 1000 clients retry at exactly `t+100ms`, then all at
`t+300ms`. Synchronised retries are the storm. Jitter spreads them out and is the single most important
part of a retry policy.

**A low attempt count.** Three total attempts, not ten. If three failed, the problem is not transient.

## A4. Circuit breaker

Named after the electrical one: when current is dangerous, cut the circuit rather than let the house
burn.

**The idea:** if a dependency is failing, stop calling it. Fail instantly instead. You lose nothing —
the call was going to fail anyway — and you gain everything: threads are freed, and the struggling
service gets a chance to recover instead of being hammered.

### Three states

```
        failure rate exceeds the threshold
   ┌──────────────────────────────────────────┐
   ▼                                          │
CLOSED ──────────────────────────────────▶ OPEN
   ▲   normal: calls pass through,       fail instantly,
   │   outcomes are recorded             no call is made
   │                                          │
   │                                          │ after waitDurationInOpenState
   │       all trial calls succeed             ▼
   └──────────────────────────────── HALF_OPEN
                                     let N trial calls through
                                          │
                                          │ a trial call fails
                                          └──────▶ OPEN
```

**CLOSED** — normal. Every result goes into a sliding window.

**OPEN** — the breaker has tripped. Calls fail immediately with `CallNotPermittedException`, in
microseconds. **No thread waits. No load reaches the sick service.**

**HALF_OPEN** — after the wait, allow a few trial calls. All succeed → CLOSED. Any fails → OPEN again.
This is how it recovers **automatically**, with no human and no restart.

### The settings that matter

| Setting | Meaning | Sensible value |
|---|---|---|
| `slidingWindowSize` | how many calls (or seconds) are measured | 10–100 |
| `failureRateThreshold` | % failures that trips it | 50% |
| `minimumNumberOfCalls` | do not judge before this many | 5–10 |
| `waitDurationInOpenState` | how long to stay open | 5–30 s |
| `permittedNumberOfCallsInHalfOpenState` | trial calls | 3 |
| `slowCallDurationThreshold` | what counts as "slow" | your timeout, or lower |
| `slowCallRateThreshold` | % slow calls that trips it | 50% |

**`minimumNumberOfCalls` prevents a stupid failure:** without it, one failed call out of one is a 100%
failure rate and the breaker opens on the very first hiccup at 3am when traffic is low.

**The slow-call settings are the ones most people skip, and they matter most.** Concept A1: slow kills
you before failing does. A breaker that only counts *errors* will sit happily CLOSED while every call
takes 5 seconds and your threads evaporate. **Counting slow calls as failures is what makes a circuit
breaker actually protect you.**

## A5. Fallback — and how to write an honest one

When the breaker is open or the call fails, what do you return?

**Good fallbacks:**
- Cached or slightly stale data ("last known event details")
- A reduced response ("recommendations unavailable" but the page still renders)
- A queued action ("we have received your request and will process it")
- **A clear, correct error** — this is often the right answer

**Bad fallbacks, which are worse than failing:**
- An empty list where the caller expects data — the user sees "no events" and believes it
- A default that looks like real data — `availableSeats: 0` when you simply do not know
- Silently swallowing the failure so nobody is alerted
- **Anything that turns "I don't know" into "here is a fact"**

**The rule: a fallback may degrade the answer, never falsify it.**

For our booking flow the honest fallback is a clear error. We **cannot** invent event details, and we
must never guess that seats are available. Booking with unknown inventory is the exact bug this whole
project is about.

## A6. Bulkhead

From ships: a hull is divided into sealed compartments, so one breach floods one compartment rather
than sinking the vessel.

**In software:** limit how many concurrent calls a single dependency may consume, so one sick dependency
cannot eat the whole thread pool.

```
WITHOUT                              WITH
200 threads, all can wait on         event-service capped at 20 concurrent
event-service                        180 threads still free for everything else
→ one slow dependency = total        → one slow dependency = one degraded feature
  outage
```

Two kinds:
- **Semaphore bulkhead** — a counter. The caller's own thread is used; over the limit, fail fast. Cheap;
  our choice.
- **Thread pool bulkhead** — a separate pool per dependency. Full isolation, but every call costs a
  thread handoff and loses `ThreadLocal` context (`SecurityContextHolder`, MDC — Phase 2a and Phase 3).

**A bulkhead converts "everything is broken" into "this one feature is broken."** That is usually the
difference between an incident and a page nobody notices.

## A7. The order the decorators run in

Resilience4j's default aspect order, outermost first:

```
Retry
 └─ CircuitBreaker
     └─ RateLimiter
         └─ TimeLimiter
             └─ Bulkhead
                 └─ your method
```

**Read it as behaviour, because the order is not arbitrary:**

- **Retry is outermost**, so each retry attempt is a *separate* call through the circuit breaker. That
  is what you want: three attempts against a dead service record three failures and help trip the
  breaker.
- **CircuitBreaker outside Bulkhead**, so when the breaker is open you fail without even taking a
  bulkhead permit.

**The consequence people get wrong:** `maxAttempts: 3` on retry combined with a breaker means a single
user request can produce 3 recorded failures. Your `minimumNumberOfCalls` is counting *attempts*, not
user requests. Size them together.

---

# PART B — Build it

## Step 1 — Dependencies

**File:** `services/booking-service/pom.xml`

```xml
        <dependency>
            <groupId>io.github.resilience4j</groupId>
            <artifactId>resilience4j-spring-boot3</artifactId>
            <version>2.2.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
        </dependency>
```

**`starter-aop` is not optional.** Resilience4j's annotations work through AOP proxies — the same
mechanism as `@Transactional` and `@Cacheable`. Without it the annotations do nothing, silently. And
**the self-invocation trap applies**: calling an annotated method from another method in the same class
bypasses every protection. (Filters doc, AOP section.)

Move the version to a property in the root POM.

## Step 2 — Circuit breaker configuration

**File:** `services/booking-service/src/main/resources/application.yml`

```yaml
resilience4j:
  circuitbreaker:
    configs:
      default:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        slowCallRateThreshold: 50
        slowCallDurationThreshold: 1s
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        registerHealthIndicator: true
        recordExceptions:
          - com.eventbooking.booking.exception.EventServiceUnavailableException
          - feign.RetryableException
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - com.eventbooking.booking.exception.EventNotFoundException
          - com.eventbooking.booking.exception.SeatsUnavailableException
          - com.eventbooking.booking.exception.EventNotBookableException
    instances:
      eventService:
        baseConfig: default
```

**The two lists at the bottom are the most important part of this file.**

`recordExceptions` — what counts as the dependency being sick. Timeouts, IO errors, 5xx.

`ignoreExceptions` — what is a **normal business outcome**. `EventNotFoundException` means the user
asked for event 999. `SeatsUnavailableException` means the event sold out. **Event-service is working
perfectly in both cases.**

**Get this wrong and you build a spectacular bug:** a popular sold-out event produces hundreds of
`SeatsUnavailable` results, the breaker counts them as failures, trips, and now **nobody can book
anything** — because one event sold out. The breaker must measure *the dependency's health*, never *the
answers the dependency gives*.

The rest, briefly:

- `COUNT_BASED` window of 10: the last 10 calls. `TIME_BASED` (the last N seconds) suits variable
  traffic better; count-based is easier to reason about while learning.
- `minimumNumberOfCalls: 5` — no judgement before five calls (concept A4).
- `slowCallDurationThreshold: 1s` with `slowCallRateThreshold: 50` — **this is the setting that saves
  you**. Concept A1: if half the calls take over a second, the breaker opens even though nothing has
  technically failed.
- `automaticTransitionFromOpenToHalfOpenEnabled: true` — move to HALF_OPEN on a timer instead of waiting
  for the next call to arrive. Recovers without traffic.
- `registerHealthIndicator: true` — the breaker's state appears in `/actuator/health`.

## Step 3 — Retry and bulkhead

Add to the same file:

```yaml
  retry:
    configs:
      default:
        maxAttempts: 3
        waitDuration: 200ms
        exponentialBackoffMultiplier: 2
        enableExponentialBackoff: true
        enableRandomizedWait: true
        randomizedWaitFactor: 0.5
        retryExceptions:
          - com.eventbooking.booking.exception.EventServiceUnavailableException
          - feign.RetryableException
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - com.eventbooking.booking.exception.EventNotFoundException
          - com.eventbooking.booking.exception.SeatsUnavailableException
          - com.eventbooking.booking.exception.EventNotBookableException
    instances:
      eventServiceRead:
        baseConfig: default

  bulkhead:
    configs:
      default:
        maxConcurrentCalls: 20
        maxWaitDuration: 0
    instances:
      eventService:
        baseConfig: default
```

**`maxAttempts: 3` means 1 original + 2 retries.** People read it as 3 retries; it is 3 attempts.

**The backoff maths**, worth computing once so the numbers are not magic:

```
attempt 1: immediate
attempt 2: wait 200ms × random(0.5 … 1.5)  → 100–300 ms
attempt 3: wait 400ms × random(0.5 … 1.5)  → 200–600 ms
worst case added latency: ~900 ms
```

**`enableRandomizedWait: true` is the jitter from concept A3** — the line that prevents a retry storm.
Never ship a retry policy without it.

**Notice the instance is named `eventServiceRead`.** That naming is deliberate and it is the heart of
this step: **we will apply retry only to `getEvent`, never to `reserveSeats`.** Reads are idempotent;
reserving seats is not. Concept A3 — retrying a reserve after a timeout books extra seats.

**`maxWaitDuration: 0` on the bulkhead**: when 20 calls are already in flight, the 21st fails
immediately rather than queueing. **Queueing to enter a bulkhead defeats its purpose** — you would be
waiting again, which is the thing you are trying to stop.

## Step 4 — Expose the state

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,circuitbreakers,circuitbreakerevents,retries,retryevents,bulkheads
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
```

`/actuator/circuitbreakerevents` is a rolling log of every state transition and every recorded call.
**It is the single most useful endpoint in this phase** — you can watch the breaker think.

In production, expose these on a separate management port, not through the public gateway.

## Step 5 — A gateway class for the dependency

Resilience4j annotations must sit on a bean method that is called *from outside* (AOP, step 1). Feign
interfaces are proxies already, so we wrap them.

**File:** `.../infrastructure/EventGateway.java`

```java
package com.eventbooking.booking.infrastructure;

import com.eventbooking.booking.exception.EventServiceUnavailableException;
import com.eventbooking.booking.infrastructure.dto.EventDto;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EventGateway {

    private static final Logger log = LoggerFactory.getLogger(EventGateway.class);

    private final EventClient eventClient;

    public EventGateway(EventClient eventClient) {
        this.eventClient = eventClient;
    }
}
```

**Why this class exists at all** — three reasons, and they are all worth stating:

1. **AOP needs an external call.** Annotating the Feign interface directly is unreliable; a wrapper bean
   is explicit and testable.
2. **One place holds the resilience policy** for this dependency. `BookingService` stays business logic.
3. **Different methods need different policies** — and that is the whole point of the next two steps.

**The read path**, with everything on it:

```java
    @CircuitBreaker(name = "eventService", fallbackMethod = "getEventFallback")
    @Retry(name = "eventServiceRead")
    @Bulkhead(name = "eventService")
    public EventDto getEvent(Long eventId) {
        return eventClient.getEvent(eventId);
    }

    private EventDto getEventFallback(Long eventId, Throwable throwable) {
        log.warn("event-service unavailable for event {}: {}", eventId, throwable.toString());
        throw new EventServiceUnavailableException(
                "Event details are temporarily unavailable. Please try again shortly.");
    }
```

**Reading an event is idempotent**, so retry is safe: call it five times, get the same answer, change
nothing.

**The fallback signature rules**, which trip up everyone the first time:

- Same parameters as the original, **plus** a `Throwable` last.
- Same return type.
- Any mismatch fails at **runtime**, not compile time, with a confusing message. Get it wrong once
  deliberately so you recognise it later.

**Our fallback throws rather than returning fake data.** Concept A5: we cannot invent an event, and
returning `availableSeats: 0` would be a lie that causes a wrong booking decision. **The fallback's job
here is to convert an ugly failure into a clear, honest one** — a friendly message and a 503 instead of
a stack trace.

**The write path, deliberately different:**

```java
    @CircuitBreaker(name = "eventService")
    @Bulkhead(name = "eventService")
    public EventDto reserveSeats(Long eventId, int quantity) {
        return eventClient.reserveSeats(eventId, quantity);
    }

    @CircuitBreaker(name = "eventService")
    @Bulkhead(name = "eventService")
    public EventDto releaseSeats(Long eventId, int quantity) {
        return eventClient.releaseSeats(eventId, quantity);
    }
```

**No `@Retry`. No fallback. This is the most important decision in the phase.**

**No retry**, because reserving seats is not idempotent (concept A3). A timeout leaves us genuinely
unsure whether the seats were taken; retrying risks taking them twice. **When you cannot tell, do not
repeat.** Phase 9 makes these operations idempotent, and *then* retry becomes safe — that ordering is
the whole point.

**No fallback**, because there is no honest degraded answer to "did the reservation happen?" Failing
loudly is correct. **A fallback that pretends a write succeeded is how systems silently lose data.**

Then change `BookingService` to depend on `EventGateway` instead of `EventClient`. Nothing else in it
changes — the resilience policy is invisible to the business logic, which is exactly right.

## Step 6 — Run and watch the breaker work

Rebuild, restart everything, and open a terminal on the events endpoint:

```bash
watch -n 1 "curl -s http://localhost:8083/actuator/circuitbreakers | jq '.circuitBreakers.eventService.state'"
```

It shows `"CLOSED"`. Make a normal booking — still `CLOSED`.

**Now stop event-service** and hammer the endpoint:

```bash
for i in $(seq 1 10); do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/v1/bookings \
    -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
    -d '{"eventId":1,"quantity":1}'
done; echo
```

The state flips to `"OPEN"`, and the response codes get faster and faster. Then look at the reasoning:

```bash
curl -s http://localhost:8083/actuator/circuitbreakerevents | jq '.circuitBreakerEvents[-8:]'
```

```json
{"type":"ERROR","creationTime":"...","errorMessage":"..."}
{"type":"ERROR", ...}
{"type":"FAILURE_RATE_EXCEEDED","failureRate":"100.0"}
{"type":"STATE_TRANSITION","stateTransition":"CLOSED_TO_OPEN"}
{"type":"NOT_PERMITTED"}
{"type":"NOT_PERMITTED"}
```

**`NOT_PERMITTED` is the whole phase in one word.** No connection was attempted. No thread waited. The
call failed in microseconds.

**Measure it, because the number is the argument:**

```bash
# breaker OPEN
time curl -s -o /dev/null -X POST http://localhost:8080/api/v1/bookings \
  -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
  -d '{"eventId":1,"quantity":1}'
```

**~5 ms, versus ~1000 ms** (the connect timeout) before the breaker opened. Two hundred times less
thread time per failed request. **That ratio is the difference between a degraded feature and a dead
platform.**

**Now watch it heal.** Start event-service and wait ~10 seconds:

```
{"type":"STATE_TRANSITION","stateTransition":"OPEN_TO_HALF_OPEN"}
{"type":"SUCCESS"}
{"type":"SUCCESS"}
{"type":"SUCCESS"}
{"type":"STATE_TRANSITION","stateTransition":"HALF_OPEN_TO_CLOSED"}
```

**Three trial calls, all successful, back to normal. Nobody restarted anything.** Self-healing
infrastructure, from twenty lines of YAML.

---

## Break it on purpose

### Drill 1 — watch retry and its cost

Turn on retry event logging and stop event-service, then make **one** booking request:

```bash
curl -s http://localhost:8083/actuator/retryevents | jq '.retryEvents[-5:]'
```

```json
{"type":"RETRY","numberOfAttempts":1,"creationTime":"...T10:00:00.100Z"}
{"type":"RETRY","numberOfAttempts":2,"creationTime":"...T10:00:00.480Z"}
{"type":"ERROR","numberOfAttempts":3}
```

**Look at the gaps between the timestamps** — around 200 ms then around 400 ms, each randomised. That is
exponential backoff with jitter, in your own data.

**Now the cost.** That single user request took ~2.9 s (three 1-second connect timeouts plus the
waits). **Retry makes a failing request slower**, and that is exactly why the circuit breaker sits
outside it: once the breaker opens, retry never runs at all.

### Drill 2 — the sold-out disaster

Temporarily move `SeatsUnavailableException` from `ignoreExceptions` into `recordExceptions`, restart,
and book a sold-out event 6 times.

**The breaker opens.** Now try to book a completely different event with plenty of seats: `503`.

**One sold-out event has taken down booking for the entire platform**, and event-service was healthy the
entire time.

This is the single most common circuit breaker misconfiguration in real systems. **A breaker measures
whether the dependency is *working*, never whether the answer was *yes*.** Put the exception back where
it belongs.

### Drill 3 — slow, not down

Add `Thread.sleep(2000)` to event-service's `getById`, restart, and make several bookings.

The breaker opens — with **zero errors**. Every call succeeded. `slowCallDurationThreshold: 1s` counted
them as failures because taking 2 seconds is a failure from the caller's point of view.

```bash
curl -s http://localhost:8083/actuator/circuitbreakerevents | jq '.circuitBreakerEvents[-4:]'
```

```json
{"type":"SUCCESS","duration":2003}
{"type":"SLOW_CALL_RATE_EXCEEDED","slowCallRate":"100.0"}
{"type":"STATE_TRANSITION","stateTransition":"CLOSED_TO_OPEN"}
```

**Comment out the two slow-call settings and repeat.** The breaker stays `CLOSED` forever while every
thread burns 2 seconds — the exact scenario from concept A1, reproduced on your laptop.

**A circuit breaker without slow-call detection does not protect you from the failure mode that
actually kills systems.** Remember the sleep and put the settings back.

### Drill 4 — the bulkhead holds a line

With the 2-second sleep still in place, fire 50 concurrent bookings:

```bash
for i in $(seq 1 50); do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/v1/bookings \
    -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
    -d '{"eventId":1,"quantity":1}' &
done; wait; echo
```

At most 20 are in flight against event-service; the rest fail instantly with a bulkhead rejection.

**Meanwhile, `GET /api/v1/bookings` still responds normally.** That is the compartment holding: one sick
dependency degraded one feature, and the rest of the service kept working. Without the bulkhead, all 50
would have occupied threads and "my bookings" would have hung too.

### Drill 5 — the reason we did not retry writes

Add `@Retry(name = "eventServiceRead")` to `reserveSeats`. Make event-service slow enough to time out
*after* it commits (a `sleep` at the end of `reserveSeats`, after the transaction). Book 1 seat.

```bash
docker exec -it ebp-postgres psql -U ebp -d event_db \
  -c "SELECT available_seats FROM events WHERE id = 1;"
```

**Three seats gone for a one-seat booking**, and the user got an error. Three separate reservations
committed; every response was lost.

**This is why step 5 has no `@Retry` on writes**, and it is a complete answer to "why do we need
idempotency?" Remove the annotation.

### Drill 6 — the fallback signature

Change the fallback to take only `(Long eventId)` and restart. The first call fails with a runtime error
about no matching fallback method.

Annoying, and worth doing once: **fallback mismatches are a runtime failure, not a compile error**, so
the first time you meet this in production you will already know the cause.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Missing `starter-aop` | Annotations silently do nothing | Add the dependency |
| Annotated method called from the same class | Protections skipped | AOP self-invocation — call through another bean |
| Business exceptions in `recordExceptions` | One sold-out event kills all bookings | `ignoreExceptions` |
| No slow-call threshold | Breaker never opens while threads die | Set both slow-call settings |
| Retry on non-idempotent writes | Duplicate reservations | Reads only, until Phase 9 |
| Retry with no jitter | Synchronised retry storm | `enableRandomizedWait: true` |
| `maxAttempts: 10` | Long latency, more load on a sick service | 3 |
| `minimumNumberOfCalls: 1` | Opens on a single blip | 5–10 |
| Fallback returning fake data | Silent wrong answers | Degrade, never falsify |
| Fallback swallowing everything | Outages become invisible | Log and alert |
| Fallback signature mismatch | Runtime error | Same params + `Throwable`, same return type |
| Breaker but no timeout | Nothing is ever recorded as failed | Timeouts are the foundation |
| Thread-pool bulkhead by default | `SecurityContext` and MDC lost | Semaphore unless you need isolation |

---

## Production considerations

- **Alert on `STATE_TRANSITION` to OPEN.** An open breaker is a real incident; without an alert it is a
  silent degradation nobody sees.
- **Export the metrics** (Micrometer → Prometheus). Failure rate and slow-call rate per dependency are
  the first graphs you will want.
- **Tune with real data.** Every number in this file is a starting guess. Watch p99 latency and set
  `slowCallDurationThreshold` from it.
- **Different policies per dependency.** A payment gateway is not a recommendation service.
- **Client-side rate limiting** (`resilience4j.ratelimiter`) when a downstream service has quotas — be a
  good citizen before they throttle you.
- **Timeouts remain the foundation.** Resilience4j cannot protect you from a call that never returns.
- **Test failures in CI** with Testcontainers + Toxiproxy (Phase 13), so resilience config is verified
  rather than assumed.
- **Graceful degradation is a product decision.** "Show cached events with a stale banner" is a design
  conversation, not something to invent in a `catch` block.

---

## Phase 7 checklist

- [ ] The breaker opens after repeated failures, visible in `/actuator/circuitbreakers`
- [ ] An open breaker fails in ~5 ms instead of ~1000 ms — you measured both
- [ ] It transitions to HALF_OPEN and closes itself when the service returns
- [ ] Retry events show exponential backoff with jitter in the timestamps
- [ ] You saw a **slow** service open the breaker with zero errors
- [ ] You saw a business exception in `recordExceptions` take down all bookings
- [ ] The bulkhead capped concurrency while other endpoints stayed healthy
- [ ] You retried a write and produced duplicate reservations
- [ ] You can explain why reads retry and writes do not
- [ ] You can explain why the fallback throws instead of returning empty data
- [ ] You can draw CLOSED → OPEN → HALF_OPEN and name what triggers each edge

---

## What Phase 8 does next

Booking is now resilient to event-service being sick. It is still not correct.

Two users clicking "book" on the last seat at the same millisecond can both pass the pre-check. Phase 6
Drill 5 already hinted at it — one 201 and four 409s, and the reason had nothing to do with our code.

Next: **concurrency.** Race conditions, lost updates, optimistic vs pessimistic locking, `@Version`
under real load, `SELECT ... FOR UPDATE`, and a Redis distributed lock — plus when each one is the wrong
tool.

**Pre-reading for Phase 8:** database isolation levels and the lost-update problem.
