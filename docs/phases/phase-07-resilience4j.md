# Phase 7 — Resilience4j: timeouts, retry, circuit breaker, bulkhead, fallback

**Time:** 5–6 hours
**Needs:** Phase 6 finished (booking calling event over Feign).
**Pre-reading:** cascading failure; the thread-pool-exhaustion story behind circuit breakers.

---

## In plain English

This phase keeps one slow or failing service from making the whole application wait. It adds limits and recovery rules around calls from booking-service to event-service.

Imagine event-service takes 10 seconds to answer. Without limits, booking workers can all end up waiting. We need to stop waiting after a chosen time, avoid repeating hopeless calls, and leave workers available for other requests.

Words used in this phase:

- **Resilience:** continuing to handle work as well as possible when something fails.
- **Dependency:** another service or tool this service needs.
- **Timeout:** the limit on how long a call waits.
- **Retry:** making another attempt after a failed call. Repeating a write is unsafe unless duplicates are handled.
- **Backoff:** waiting between retry attempts. Exponential backoff increases that wait each time.
- **Jitter:** adding a small random variation so clients do not all retry together.
- **Circuit breaker:** temporarily stops new calls to a service with too many recent failures or slow calls.
- **Fallback:** the response used when the usual operation cannot finish.
- **Bulkhead:** a limit on how many calls may use one dependency at the same time.
- **Rate limiter:** a limit on how many requests may start in a period of time.

Start with the failure story in A1. For every tool, ask which part of that story it helps prevent.

---

# PART A — Understand the ideas

## A1. How one slow service kills a whole platform

Follow one slow call through the services. The tools in this phase each limit a different part of the resulting delay.

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

**A service can be running but unable to serve more requests.** Event-service slowed down, booking-service workers waited for it, and gateway requests then waited for booking-service.

**Waiting uses capacity.** A failed call that ends quickly frees its worker quickly. A call that takes 5 seconds keeps that worker occupied. With enough such calls, new requests have no worker available.

The tools below limit waits, repeated attempts, or the number of calls affected.

## A2. Timeout — the foundation

Every network call needs a timeout. A caller must have a limit on how long it will wait, even if the other service never replies. Phase 6 configured these limits for Feign.

**Choose timeouts from the whole request budget.** If a request must finish in 2 seconds and makes two calls one after the other, each needs less than a second so there is time for the remaining work. **SLA** means an agreed service target; here, the target includes response time.

`TimeLimiter` can limit waits for asynchronous work represented by a `CompletableFuture`. For our blocking Feign calls, use the HTTP client's connection and read timeouts from Phase 6. A timeout does not guarantee that work already started on another service has stopped.

## A3. Retry — useful and dangerous in equal measure

Some failures are temporary: a dropped connection, a service restarting, or a brief garbage-collection pause while Java cleans up memory. The same request may work a little later. This is when a retry can help.

### When retrying is wrong

**1. Non-idempotent operations.** This is the big one.

```
POST /events/1/reserve?quantity=2
  → event-service reserves the seats, commits
  → the response is lost (timeout)
  → we retry
  → event-service reserves 2 MORE seats
```

**The user asked for 2 seats, but 4 were reserved.** The first call may have succeeded even though its reply timed out. Retry only when repeating the operation has the same final effect. HTTP defines methods such as `GET`, `PUT`, and `DELETE` that way; a booking `POST` needs added duplicate protection from Phase 9.

**2. 4xx errors.** The request is wrong. Retrying sends the identical wrong request.

**3. A service that remains unavailable.** Repeating the call adds load and makes the caller wait longer. A circuit breaker can pause those calls while the service recovers.

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

**Retries can turn a short problem into a longer outage.** Many clients repeating work together add more traffic just when a service is struggling. Use these limits:

**Exponential backoff** increases the delay between attempts, for example 100 ms, then 200 ms, then 400 ms. It gives the other service time to recover.

**Jitter** adds random variation to each delay. Without it, many callers can retry at exactly the same times and overload the service again. Different delays spread those requests out.

**Keep the attempt count low.** This guide uses three total attempts. After repeated failure, return a clear failure rather than continuing to add load.

## A4. Circuit breaker

An electrical circuit breaker cuts off current when there is a problem. The software version pauses calls when a service has too many failures or slow responses.

**The idea:** stop sending ordinary calls to a service that is repeatedly failing. Reject them quickly for a while. This frees callers and reduces pressure on the failing service while it recovers.

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

**CLOSED** means calls are allowed. The breaker records recent results in a **sliding window**: a set of recent calls, or calls within a recent time period.

**OPEN** means new calls are rejected immediately with `CallNotPermittedException`. They do not reach the failing service, so workers do not wait for those calls.

**HALF_OPEN** means a few test calls are allowed after the pause. Their results decide whether ordinary calls resume or the pause continues. This lets the breaker recover without a restart.

### The settings that matter

| Setting | Meaning | Useful value |
|---|---|---|
| `slidingWindowSize` | how many calls (or seconds) are measured | 10–100 |
| `failureRateThreshold` | % failures that trips it | 50% |
| `minimumNumberOfCalls` | do not judge before this many | 5–10 |
| `waitDurationInOpenState` | how long to stay open | 5–30 s |
| `permittedNumberOfCallsInHalfOpenState` | trial calls | 3 |
| `slowCallDurationThreshold` | what counts as "slow" | your timeout, or lower |
| `slowCallRateThreshold` | % slow calls that trips it | 50% |

`minimumNumberOfCalls` requires enough results before judging the failure rate. Otherwise the very first failed call would be 1 failure out of 1 call, or 100%, and could open the breaker too early.

**Count slow calls as well as errors.** A service may return successful responses but take so long that callers run out of workers. The slow-call settings let the breaker react to that case too.

## A5. Fallback — and how to write an honest one

When the breaker is open or the call fails, what do you return?

**Good fallbacks:**
- Cached or slightly out of date data ("last known event details")
- A reduced response ("recommendations unavailable" but the page still renders)
- A queued action ("we have received your request and will process it")
- **A clear, correct error** — this is often the right answer

**Bad fallbacks, which are worse than failing:**
- An empty list where the caller expects data — the user sees "no events" and believes it
- A default that looks like real data — `availableSeats: 0` when you simply do not know
- Without a clear error swallowing the failure so nobody is alerted
- **Anything that turns "I don't know" into "here is a fact"**

**A fallback must be truthful.** It may return less data or a clear error. It must not present a guess as a confirmed result.

For booking, a clear error is the useful fallback. If we cannot confirm seat availability, we cannot safely pretend a reservation succeeded.

## A6. Bulkhead

A ship has separate watertight sections so a leak affects only part of it. A software bulkhead applies a similar limit to shared workers or calls.

**Limit calls to each dependency.** If event-service is slow, only a chosen number of workers may wait for it. Other workers remain available for other work.

```
WITHOUT                              WITH
200 threads, all can wait on         event-service capped at 20 concurrent
event-service                        180 threads still free for everything else
→ one slow dependency = total        → one slow dependency = one degraded feature
  outage
```

Two kinds:
- **Semaphore bulkhead:** counts calls in progress. Calls use the caller's thread. Once the limit is reached, extra calls fail quickly. We use this type.
- **Thread pool bulkhead:** runs the work on a separate set of threads. This separates workers but adds a thread switch. Values kept on the original thread, such as `SecurityContextHolder` and MDC, are not automatically carried across.

**A bulkhead limits how far a problem spreads.** One feature may fail while other features still have workers available.

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

**The wrappers run in a particular order.** Read the order as the path taken by each call:

- **Retry wraps the other checks.** Each new attempt passes through the circuit breaker, so each failed attempt can be counted separately.
- **CircuitBreaker wraps Bulkhead.** An open breaker rejects a call before it takes one of the bulkhead's available places.

**Count attempts, not just user requests.** With `maxAttempts: 3`, one request can add three failures to the breaker's record. Choose the retry and breaker settings together.

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

**Include `starter-aop`.** These annotations use Spring-generated wrappers, just like `@Transactional` and `@Cacheable`. Without that support the protection is not applied. Calling an annotated method from another method on the same object also skips the wrapper. This is the self-invocation problem described in the filters guide.

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

**A sold-out event is not evidence of a broken service.** If `SeatsUnavailable` counts as a dependency failure, one popular sold-out event could open the breaker and block unrelated bookings. Count service-health failures, not valid business rejections.

The rest, briefly:

- `COUNT_BASED` with 10 uses the last 10 calls. `TIME_BASED` uses calls within a recent number of seconds. Count-based is easier to inspect in this exercise.
- `minimumNumberOfCalls: 5` waits for at least five calls before judging the rates.
- `slowCallDurationThreshold: 1s` and `slowCallRateThreshold: 50` treat calls over 1 second as slow. If half the measured calls are slow, the breaker can open even if they returned successful responses.
- `automaticTransitionFromOpenToHalfOpenEnabled: true` moves to the test-call state after the configured delay without needing a new ordinary call to trigger it.
- `registerHealthIndicator: true` includes the breaker state in `/actuator/health`.

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

**`eventServiceRead` applies retry only to `getEvent`.** Reading details can be repeated without reserving more seats. Do not apply the same retry to `reserveSeats`: a timed-out attempt may already have reserved them.

**`maxWaitDuration: 0` means no queue for this bulkhead.** With 20 calls already running, the 21st is rejected immediately. Waiting for a place would keep another caller occupied, which this limit is intended to avoid.

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

**The fallback throws a clear service-unavailable error.** It does not invent an event or return `availableSeats: 0` when the real value is unknown. That gives the client a useful 503 response without claiming a false booking result.

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

**Do not retry reservations yet.** A timeout may hide a successful reservation, so another attempt could reserve the seats twice. First make repeated writes safe using the duplicate protection introduced in Phase 9.

**No fallback**, because there is no honest degraded answer to "did the reservation happen?" Failing
loudly is correct. **A fallback that pretends a write succeeded is how systems without a clear error lose data.**

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

**Retries add delay to a failing request.** Several connection waits and pauses can make this request take roughly 3 seconds. In the wrapper order described in A7, each attempt passes through the circuit breaker. An open breaker rejects attempts quickly rather than sending them to the failed service.

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

With the 2-second sleep still in place, fire 50 happening at the same time bookings:

```bash
for i in $(seq 1 50); do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/v1/bookings \
    -H "Authorization: Bearer $USER_TOKEN" -H "Content-Type: application/json" \
    -d '{"eventId":1,"quantity":1}' &
done; wait; echo
```

At most 20 are in flight against event-service; the rest fail instantly with a bulkhead rejection.

**`GET /api/v1/bookings` should still work.** The limited number of calls waiting for event-service leaves workers available for reading existing bookings. The test shows whether one slow dependency stays limited to the affected feature.

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
| Missing `starter-aop` | Annotations without a clear error do nothing | Add the dependency |
| Annotated method called from the same class | Protections skipped | AOP self-invocation — call through another bean |
| Business exceptions in `recordExceptions` | One sold-out event kills all bookings | `ignoreExceptions` |
| No slow-call threshold | Breaker never opens while threads die | Set both slow-call settings |
| Retry on non-idempotent writes | Duplicate reservations | Reads only, until Phase 9 |
| Retry with no jitter | Synchronised retry storm | `enableRandomizedWait: true` |
| `maxAttempts: 10` | Long time taken to get a response, more load on a sick service | 3 |
| `minimumNumberOfCalls: 1` | Opens on a single blip | 5–10 |
| Fallback returning fake data | Silent wrong answers | Degrade, never falsify |
| Fallback swallowing everything | Outages become invisible | Log and alert |
| Fallback signature mismatch | Runtime error | Same params + `Throwable`, same return type |
| Breaker but no timeout | Nothing is ever recorded as failed | Timeouts are the foundation |
| Thread-pool bulkhead by default | `SecurityContext` and MDC lost | Semaphore unless you need isolation |

---

## Notes for a real running system

- **Alert when the breaker changes to OPEN**, including a `STATE_TRANSITION` event. It signals repeated failures or slow calls.
- **Collect measurements** through Micrometer and Prometheus. Start with failure rate and slow-call rate for each dependency.
- **Tune settings using measurements.** **p99 response time** is the time within which 99% of measured calls finish. It can help choose `slowCallDurationThreshold`.
- **Choose rules per dependency.** Payment processing and optional recommendations have different failure costs.
- **Use client rate limits** through `resilience4j.ratelimiter` when a called service limits allowed traffic.
- **Keep client timeouts.** A circuit breaker does not by itself stop every call already waiting.
- **Test failures in CI** with Testcontainers and Toxiproxy, as described in Phase 13.
- **Agree on fallback behaviour with the product needs.** Showing an old cached event with a clear notice can be useful; silently inventing data cannot.

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
