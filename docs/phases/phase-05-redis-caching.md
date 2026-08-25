# Phase 5 — Redis caching: cache-aside, TTL, invalidation, and what breaks

**Time:** 4–5 hours
**Needs:** Phase 4 finished (event-service serving events).
**Pre-reading:** cache hit vs miss, TTL, and why cache invalidation is famously hard.

---

# PART A — Concepts

## A1. Why cache at all

`GET /api/v1/events/1` currently does: HTTP → Tomcat thread → Hibernate → JDBC → Postgres → parse rows
→ build objects → JSON. Roughly **5–20 ms**, most of it waiting on the database.

The same request from Redis: **0.2–1 ms**. Not because Redis is magic, but because it is an in-memory
hash map reached over one TCP round trip, with no query planner, no disk, and no object mapping.

Rough numbers worth carrying in your head:

| Operation | Time |
|---|---|
| L1 CPU cache | ~1 ns |
| RAM | ~100 ns |
| Redis on the same network | ~200 µs |
| Postgres simple indexed query | ~1–5 ms |
| Postgres complex join | ~10–100 ms |
| Disk seek (spinning) | ~10 ms |

**But speed is the smaller half of the argument.** The bigger one is **load**. 1000 requests/second for
the same popular event is 1000 database queries per second for a row that has not changed all day. Your
database has finite connections (Phase 4: pool of 10). Caching turns that into ~1 query per TTL window,
and the database gets to spend its connections on writes.

### When caching is right, and when it is a trap

**Good candidates:** read far more often than written; the same values requested repeatedly; slightly
stale data is acceptable; expensive to compute.

**Bad candidates:** written as often as read; every request wants different data (no reuse); **stale
data is dangerous**.

**Our decisions in this service:**

| Data | Cache? | Why |
|---|---|---|
| Event details (title, venue, date) | **yes** | Read constantly, changed rarely |
| Published events list (page 1) | **yes, short TTL** | Hot, mostly stable |
| `availableSeats` | **no — read from the database** | Changes on every booking; stale here means double booking |

**That third row is the important one.** It is tempting to cache the whole `Event` including seat count.
Do not. A cached seat count is a *lie about inventory*, and Phase 8 is entirely about not lying about
inventory. We will cache the event and treat `availableSeats` in the cached copy as **display-only**;
the booking path always reads the live row.

Being able to say "we deliberately did not cache X" is a stronger interview signal than caching
everything.

## A2. Caching patterns

### Cache-aside (lazy loading) — what we use

The application manages the cache; the cache knows nothing about the database.

```
read:
  value = cache.get(key)
  if value != null:  return value           ← HIT
  value = database.load(key)                ← MISS
  cache.put(key, value, ttl)
  return value

write:
  database.save(value)
  cache.delete(key)                         ← invalidate, do not update
```

**Why it wins:** only requested data is cached (no wasted memory), and if the cache dies everything
still works — slower, but correct. Failure of the cache is not failure of the system.

**Its weaknesses**, both real: the first request after a miss is slow, and there is a window where the
database has new data and the cache still holds the old.

### The others, briefly

- **Read-through** — the cache itself loads from the database on a miss. Cleaner application code, but
  the cache becomes infrastructure that must know your schema.
- **Write-through** — write to the cache and the database together, synchronously. Cache is never
  stale; every write is slower.
- **Write-behind (write-back)** — write to the cache, flush to the database later. Very fast writes;
  **you lose data if the cache dies before the flush.** Only for data you can afford to lose.

**Cache-aside is the default for a reason.** Spring's `@Cacheable` implements exactly this pattern.

### Why delete on write, never update

Both look reasonable:

```java
cache.put(key, newValue);   // update
cache.evict(key);           // delete
```

**Delete is safer, and this is a genuinely subtle point.** With two concurrent writers:

```
Writer A: DB write v1 ──────────▶ cache.put(v1)
Writer B:      DB write v2 ─▶ cache.put(v2)
                                       ▲
   final DB state = v2, final cache state = v1.  Permanently wrong.
```

Interleaving is possible because the database write and the cache write are two separate operations
with no shared lock. With `evict`, the worst outcome is an extra cache miss — the next reader reloads
whatever the database actually holds. **Wrong-but-fast recovers; wrong-and-cached does not.**

## A3. TTL and eviction

**TTL (time to live)** — how long a key survives. It is your safety net: even if every invalidation you
wrote is buggy, the cache self-corrects within the TTL.

Picking one is a business question, not a technical one: *how stale may this be?*

| Data | TTL | Reasoning |
|---|---|---|
| Event details | 10 min | Changes rarely; 10 minutes of staleness is harmless |
| Event list | 60 s | New events should appear quickly |
| Seat counts | **not cached** | Any staleness is a correctness bug |

**Eviction** is different from expiry: it is what Redis does when it hits `maxmemory`. Policies include
`allkeys-lru` (evict least recently used — the usual choice for a pure cache), `volatile-ttl` (evict
the soonest to expire), and `noeviction` (reject writes — correct for a queue, fatal for a cache).

**Set `maxmemory` and a policy.** With the default `noeviction`, a full Redis starts refusing writes,
and your "optional" cache becomes an outage.

## A4. The four ways caches go wrong

### 1. Stale data

Someone updates the database and the cache still serves the old value until eviction or TTL. **This is
inherent, not a bug.** You manage it with short TTLs and eviction on write; you do not eliminate it.

### 2. Cache stampede (thundering herd)

A popular key expires. In that instant 500 concurrent requests all miss, and all 500 hit the database
for the same row.

```
      TTL expires
          │
 500 req ─┼──▶ all miss ──▶ 500 identical queries ──▶ database melts
```

Fixes: a short lock so only one request recomputes (Phase 8's distributed lock), recomputing *before*
expiry, or **adding random jitter to TTLs** so a thousand keys written together do not all expire
together. Jitter is the cheapest and helps most.

### 3. Cache penetration

Requests for a key that **does not exist anywhere**. Every one misses the cache and hits the database.
`GET /api/v1/events/999999999` in a loop is a trivial denial-of-service.

Fix: **cache the "not found" answer too**, with a short TTL. Spring does this if you allow null caching
— but be careful, because now a newly created id may return "not found" from the cache for a few
seconds.

### 4. Cache avalanche

Everything expires at once (a mass reload, a restart, a shared TTL) and the entire load lands on the
database simultaneously. Same fix as the stampede: jitter, plus warming critical keys on startup.

## A5. Redis facts that change how you use it

**Redis is single-threaded for command execution.** One command at a time, no locks, no race conditions
*inside* Redis. This is why `INCR` and `SETNX` are atomic for free, and it is the foundation of the
distributed lock in Phase 8.

It also means **one slow command blocks everything**. `KEYS *` on a million keys is a production
incident. Use `SCAN`.

**Persistence:** RDB (periodic snapshots — fast, loses recent writes) and AOF (append-only log — safer,
slower). For a pure cache you can turn both off; the data is reconstructible by definition. We left AOF
on in Phase 1 because Phase 8 stores locks here.

**Data types** worth knowing: `String` (any bytes — our JSON), `Hash` (field/value maps), `List`, `Set`,
`Sorted Set` (leaderboards, delayed queues), plus TTL on any key.

**Distributed vs local cache:** a local in-process cache (Caffeine) is faster still — nanoseconds, no
network — but each instance has its own copy, so three instances mean three different stale versions and
no way to invalidate them all. Redis is shared, so one `evict` affects everyone. For anything a user can
change, use the shared cache.

## A6. Serialization — the trap nobody warns you about

Something must turn your `Event` object into bytes for Redis. Spring's default is **JDK serialization**,
which is wrong here for three reasons: the output is unreadable in `redis-cli`, it requires
`implements Serializable` everywhere, and it breaks the moment a class changes.

**Use JSON.** Readable, language-neutral, tolerant of added fields.

**But JSON caching creates a versioning problem you must plan for.** Add a field to the DTO and deploy;
old cached JSON has no such field. Jackson handles a *missing* field fine (null/default) — but an
*unknown* field, when you remove one, throws unless you configure it not to.

**Rules:**
1. **Cache DTOs, never entities.** An entity carries Hibernate proxies and lazy collections that do not
   serialise sanely — and a deserialised entity is *detached*, so a stray `save()` on it can overwrite
   real data.
2. Configure Jackson to ignore unknown properties.
3. **Put a version in the cache key prefix** (`events:v1:1`). Deploying a breaking DTO change then
   means bumping to `v2`, and the old keys simply expire. This costs nothing now and saves a real
   incident later.

---

# PART B — Build it

## Step 1 — Dependencies

**File:** `services/event-service/pom.xml`

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>
```

The blocking client, not the reactive one — event-service is Tomcat and Spring MVC. The gateway used
the reactive variant because it runs on Netty (Phase 3).

`starter-data-redis` uses **Lettuce** by default: netty-based, thread-safe, one shared connection.
Jedis, the older client, needs a connection pool because its connections are not thread-safe. Lettuce
is the right default and you rarely need to think about it.

## Step 2 — Connection config

**File:** `application.yml`

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      connect-timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2

cache:
  events:
    ttl: PT10M
    list-ttl: PT60S
```

**`timeout: 2000ms` is the setting that matters most.** Without it, a hung Redis makes every request
wait indefinitely — the cache, which exists to make things faster, becomes the reason nothing responds.

Think about what the timeout means: **a cache lookup should never take longer than the database query
it replaces.** If Redis needs more than 2 seconds, going to Postgres directly is strictly better. Some
teams set it as low as 200 ms for exactly this reason.

## Step 3 — Cache configuration

**File:** `.../config/CacheConfig.java`

Start with the class and the JSON serializer:

```java
package com.eventbooking.event.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String EVENT_CACHE = "events";
    public static final String EVENT_LIST_CACHE = "eventLists";

    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .findAndRegisterModules();
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
```

`@EnableCaching` switches on the caching **AOP proxies**. Without it, `@Cacheable` is an inert
annotation and your code silently runs uncached — the same class of silent failure as a missing
`@Transactional` (Phase 4, Drill 5). And it is AOP, so **the self-invocation trap applies**: calling a
`@Cacheable` method from another method in the same class skips the cache entirely.

`JavaTimeModule` is required or `Instant` serialisation throws. Our `EventResponse` has three `Instant`
fields, so this line is not optional.

**Now the cache manager.** Add:

```java
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          CacheProperties properties) {

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()))
                .disableCachingNullValues()
                .prefixCacheNameWith("ebp:v1:");

        Map<String, RedisCacheConfiguration> perCache = Map.of(
                EVENT_CACHE, base.entryTtl(properties.ttl()),
                EVENT_LIST_CACHE, base.entryTtl(properties.listTtl())
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base.entryTtl(Duration.ofMinutes(5)))
                .withInitialCacheConfigurations(perCache)
                .build();
    }
```

**Every line is a decision:**

`StringRedisSerializer` for keys — so `redis-cli KEYS 'ebp:v1:*'` shows readable keys. The default JDK
serializer produces binary keys you cannot search or debug.

`jsonSerializer()` for values — concept A6.

**`disableCachingNullValues()`** — with it, a `null` return is not cached, which means `GET /events/999`
hits the database every time: **cache penetration** (concept A4). We accept that here because our
service throws `EventNotFoundException` rather than returning null, so nothing null ever reaches the
cache. Know the trade: if you switch to returning `Optional.empty()`, enable null caching with a short
TTL.

**`prefixCacheNameWith("ebp:v1:")`** — two wins. `ebp:` namespaces this app so several apps can share
one Redis without colliding. **`v1:` is the schema version from concept A6** — change the cached DTO
shape incompatibly and you bump to `v2:`, orphaning old keys instead of deserialising them into
exceptions.

**Different TTLs per cache** — 10 minutes for details, 60 seconds for lists. One TTL for everything is
the usual lazy default and it is always wrong for something.

**File:** `.../config/CacheProperties.java`

```java
package com.eventbooking.event.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "cache.events")
public record CacheProperties(Duration ttl, Duration listTtl) {
}
```

Add `@ConfigurationPropertiesScan` to `EventServiceApplication`. Same reasoning as Phase 2b, step 2:
typed, validated at startup, testable without Spring.

## Step 4 — Survive Redis being down

**File:** `.../config/CacheConfig.java`, add:

```java
    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {

            private final Logger log = LoggerFactory.getLogger("CacheErrorHandler");

            @Override
            public void handleCacheGetError(RuntimeException ex, Cache cache, Object key) {
                log.warn("Cache GET failed [{}::{}] - falling back to database: {}",
                        cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException ex, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed [{}::{}]: {}", cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException ex, Cache cache, Object key) {
                log.error("Cache EVICT failed [{}::{}] - STALE DATA RISK: {}",
                        cache.getName(), key, ex.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException ex, Cache cache) {
                log.error("Cache CLEAR failed [{}]: {}", cache.getName(), ex.getMessage());
            }
        };
    }
```

Then have `CacheConfig` implement `CachingConfigurer` and override `errorHandler()` to return this
bean.

**This class is the difference between "the cache is down" and "the site is down."**

By default, a Redis failure throws and your request returns 500 — a service that worked perfectly well
without a cache last week now fails *because* you added one. That is the opposite of what a cache is
for. Swallowing the error means a Redis outage degrades you to Phase 4 performance. Slower, correct,
alive.

**Notice the log levels, and the reasoning behind them.** GET and PUT failures are `warn`: you lost
speed. **EVICT failure is `error`**, because the database now holds new data and the cache holds old
data with no invalidation — you are actively serving wrong answers until the TTL expires. Those two
failures deserve different levels of panic.

**The honest limit:** this makes reads and writes survive, but it cannot fix the staleness a failed
evict causes. That is what TTL is for. **There is no configuration that gives you a cache with no
consistency cost.**

## Step 5 — Cache the read path

**File:** `.../application/EventService.java`

We cache **DTOs, not entities** (concept A6), so the caching methods return `EventResponse`:

```java
    @Cacheable(cacheNames = CacheConfig.EVENT_CACHE, key = "#id")
    @Transactional(readOnly = true)
    public EventResponse getEventResponse(Long id) {
        log.debug("Cache miss - loading event {} from database", id);
        return EventResponse.from(getById(id));
    }
```

**What Spring generates around this method:**

```
call getEventResponse(1)
   │
   ├─ compute key: "ebp:v1:events::1"
   ├─ GET that key from Redis
   │     found?  → deserialise JSON → return.  Method body NEVER RUNS.
   │     missing? ↓
   ├─ run the method body (database, mapping)
   ├─ SET the key with a 10-minute TTL
   └─ return
```

`key = "#id"` is **SpEL** referring to the parameter. Without an explicit key, Spring uses all
parameters combined — fine here, dangerous when a method takes a `Pageable` or an object whose
`hashCode` is not stable. **Always set the key explicitly**; an unstable key means a cache that never
hits and quietly wastes memory.

The `log.debug` line is deliberate: it only prints on a miss, so your logs show you the hit rate for
free.

**Keep `getById` returning the entity**, uncached, for internal use. Seat reservation must operate on a
live managed entity — concept A1's third row. **Two methods, two purposes: cached DTO for display, live
entity for correctness.**

**Now the list:**

```java
    @Cacheable(cacheNames = CacheConfig.EVENT_LIST_CACHE,
               key = "'published:' + #pageable.pageNumber + ':' + #pageable.pageSize")
    @Transactional(readOnly = true)
    public PageResponse<EventResponse> listPublishedCached(Pageable pageable) {
        return PageResponse.from(
                eventRepository.findByStatusAndStartsAtAfter(
                        EventStatus.PUBLISHED, Instant.now(), pageable),
                EventResponse::from);
    }
```

**The explicit key is doing real work here.** A `Pageable` has no cache-friendly `toString`, and if the
sort field ends up in the key you get a separate entry for every sort combination a client tries — an
unbounded key space an attacker can inflate deliberately. We key on page number and size only, which
means **this cache is correct only for the default sort**. Note that in a comment; if you later expose
sorting, the key must include it.

**Why only 60 seconds?** This query says "published events starting after `Instant.now()`" — the answer
changes as time passes, independently of any write. A long TTL would keep showing events that already
started. **Time-dependent queries need short TTLs.** That is a subtle, real bug class.

Update the controller to call the cached methods.

## Step 6 — Invalidate on write

**File:** `.../application/EventService.java`

```java
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.EVENT_CACHE, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.EVENT_LIST_CACHE, allEntries = true)
    })
    @Transactional
    public Event update(Long id, CreateEventRequest request) {
        Event event = getById(id);
        event.updateDetails(request.title(), request.description(), request.venue(),
                request.startsAt(), request.priceCents());
        log.info("Updated event id={} - caches evicted", id);
        return event;
    }
```

Apply the same `@Caching` block to `publish` and `cancel`.

**Three things here, each worth understanding:**

**`@CacheEvict`, not `@CachePut`.** Concept A2 — delete, never update. A lost update race leaves a
permanently wrong cached value; a delete leaves at worst one extra miss.

**`allEntries = true` on the list cache.** We cannot know which page contains this event — publishing
one can shift every page. Blowing away all list entries is crude and correct. The cost is bounded by
the 60-second TTL: at most a minute of recomputation. **Crude and correct beats clever and wrong**, and
if the cost ever hurts, the answer is a shorter TTL, not smarter invalidation.

**Now the part most people get wrong.** `@CacheEvict` runs when the method **returns**, and by default
that is *before* the transaction commits.

```
method body runs
  ↓
cache evicted           ← here
  ↓
transaction commits     ← database updated here
```

There is a window — small, but real — where the cache is empty and the database still holds the old
row. A read landing in that window caches the **old** value, and now you are stale until TTL with no
further eviction coming.

Setting `beforeInvocation = false` (the default) does not fix it; the ordering is the AOP proxy
ordering, and the transaction proxy is on the outside. The robust fixes, in order of effort:

1. Accept it — the window is milliseconds and TTL bounds the damage. **This is our choice.**
2. Evict again *after* commit, using `TransactionSynchronizationManager.registerSynchronization(...)`
   with an `afterCommit` callback.
3. Publish a domain event on commit (Phase 11's outbox) and evict from its handler.

**Know that this window exists and can name the fix.** That is a genuinely senior-level detail, and
almost nobody mentions it unprompted.

## Step 7 — Run and watch it work

Rebuild and restart event-service. Open a second terminal:

```bash
docker exec -it ebp-redis redis-cli MONITOR
```

`MONITOR` prints every command Redis receives. Now, in another terminal:

```bash
# first call — MISS
time curl -s http://localhost:8080/api/v1/events/1 > /dev/null

# second call — HIT
time curl -s http://localhost:8080/api/v1/events/1 > /dev/null
```

In the MONITOR window:

```
"GET" "ebp:v1:events::1"          ← miss, returns nil
"SET" "ebp:v1:events::1" "{...}" "PX" "600000"
"GET" "ebp:v1:events::1"          ← hit, returns the JSON
```

The second `curl` is typically 3–10× faster, and the event-service log shows the "Cache miss" line only
once.

**Inspect what is actually stored:**

```bash
docker exec -it ebp-redis redis-cli KEYS 'ebp:v1:*'
docker exec -it ebp-redis redis-cli GET 'ebp:v1:events::1'
docker exec -it ebp-redis redis-cli TTL 'ebp:v1:events::1'
```

Readable JSON, and a TTL counting down from 600. **That readability is why we chose the JSON and String
serializers** — with JDK serialization this would be binary noise.

**Watch an eviction:**

```bash
curl -s -X POST http://localhost:8080/api/v1/events/1/cancel \
  -H "Authorization: Bearer $ADMIN_TOKEN" > /dev/null

docker exec -it ebp-redis redis-cli KEYS 'ebp:v1:*'
```

The key is gone. The next read repopulates it with the new status.

---

## Break it on purpose

### Drill 1 — kill Redis

```bash
docker compose stop redis
curl -s http://localhost:8080/api/v1/events/1 | jq
```

**It still works.** Slower, and the log shows:

```
WARN CacheErrorHandler - Cache GET failed [events::1] - falling back to database
```

Now comment out the `cacheErrorHandler` bean, restart, and try again: **500 Internal Server Error**.

**That is the whole lesson of this phase in two commands.** A cache is an optimisation. An optimisation
that can take down the service is a liability. Ten lines of error handler is the difference.

Start Redis again.

### Drill 2 — manufacture stale data

Bypass the application and change the database directly, exactly as a rogue migration or a manual fix
would:

```bash
docker exec -it ebp-postgres psql -U ebp -d event_db \
  -c "UPDATE events SET title = 'CHANGED IN DB' WHERE id = 1;"

curl -s http://localhost:8080/api/v1/events/1 | jq -r .title
```

Still the old title. For up to 10 minutes.

**Nothing is broken.** The cache was never told. This is concept A4 in the flesh, and it is why *any*
write path that bypasses your application — an admin script, a batch job, a second service — is a
correctness problem, not just a style problem. **One writer, or explicit invalidation. There is no
third option.**

Force it:

```bash
docker exec -it ebp-redis redis-cli DEL 'ebp:v1:events::1'
```

### Drill 3 — cache penetration

```bash
for i in $(seq 1 20); do curl -s -o /dev/null http://localhost:8080/api/v1/events/999999; done
docker exec -it ebp-redis redis-cli KEYS 'ebp:v1:*'
```

No key was created, and every one of those 20 requests reached Postgres. Now imagine 10,000 requests a
second for random non-existent ids.

**That is a working denial-of-service against your database, through a cache that appeared to be
protecting it.** Fix: cache the negative result briefly, or validate ids at the edge, or a Bloom filter
at large scale. Decide which, and understand why `disableCachingNullValues()` was a trade-off and not a
default to copy blindly.

### Drill 4 — the stampede

```bash
docker exec -it ebp-redis redis-cli DEL 'ebp:v1:events::1'

for i in $(seq 1 50); do
  curl -s -o /dev/null http://localhost:8080/api/v1/events/1 &
done
wait
```

Count the "Cache miss" lines in the event-service log. It will be **several**, not one — every request
that arrived before the first `SET` completed also missed and also queried the database.

Fifty is harmless. Fifty thousand on a popular key is an incident. **Concept A4's thundering herd, on
your laptop.** Phase 8's distributed lock is one fix; TTL jitter is the cheaper one.

### Drill 5 — the serialization trap

Add a field to `EventResponse` and restart **without** clearing Redis. Old cached JSON lacks the field,
so it deserialises as null — mostly harmless.

Now **remove** a field and restart. Jackson meets an unknown property and throws, and your reads fail
until the TTL expires or you flush.

**This is a real deployment incident**, and it is why `v1:` sits in the key prefix. Bump it to `v2:` and
the old keys become invisible immediately instead of poisoning every read.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Forgot `@EnableCaching` | Nothing is cached, no error | Add it |
| `@Cacheable` called from the same class | Cache silently skipped | AOP self-invocation — call through another bean |
| Caching entities instead of DTOs | Proxy/lazy errors; detached-entity writes | Cache DTOs |
| JDK serialization | Unreadable keys, breaks on class change | JSON |
| No `CacheErrorHandler` | Redis outage becomes a service outage | Log and fall through |
| No timeout on the Redis client | A hung Redis hangs every request | 2 s or less |
| `@CachePut` on write | Lost-update race leaves a wrong value cached | `@CacheEvict` |
| Caching volatile data (seat counts) | Overselling | Do not cache inventory |
| One TTL for everything | Something is always wrong | Per-cache TTLs |
| Implicit cache keys | Never hits, or unbounded key space | Explicit `key = "#id"` |
| `KEYS *` in production | Blocks single-threaded Redis | `SCAN` |
| `maxmemory-policy noeviction` | Writes rejected when full | `allkeys-lru` for a cache |
| Writing to the database outside the app | Permanent staleness | One writer, or explicit invalidation |

---

## Production considerations

- **`maxmemory` + `allkeys-lru`.** Without a memory cap, Redis grows until the OOM killer intervenes.
- **TTL jitter:** `ttl + random(0..10%)` so keys written together do not expire together.
- **Monitor the hit rate** (`redis-cli INFO stats` → `keyspace_hits` / `keyspace_misses`). Below ~80%,
  your caching is costing more than it saves.
- **Redis Sentinel or Cluster** for high availability. A single Redis is a single point of *slowness*,
  and with the error handler above, not a single point of failure.
- **Never cache anything security-sensitive** — permissions, tokens — without very short TTLs. Stale
  permissions are a vulnerability, not an inconvenience.
- **Do not use the cache as a database.** If losing all keys causes data loss, that data was never
  cache.
- **Warm critical keys on startup** if a cold cache would overwhelm the database after a deploy.
- **Watch out for `@Cacheable` on methods with side effects** — on a hit the body never runs, so any
  logging, auditing or metric inside it silently disappears.

---

## Phase 5 checklist

- [ ] Second call to the same event is measurably faster
- [ ] `redis-cli MONITOR` shows GET/SET with your `ebp:v1:` prefix
- [ ] Cached values are readable JSON with a TTL counting down
- [ ] Updating an event evicts its key
- [ ] Redis down → service still serves reads (you removed the error handler and saw the 500)
- [ ] You changed the database directly and watched the cache serve stale data for minutes
- [ ] You reproduced cache penetration with a non-existent id
- [ ] You reproduced a stampede with 50 parallel requests
- [ ] You can explain why we do **not** cache `availableSeats`
- [ ] You can explain why eviction beats update, with the interleaving that proves it
- [ ] You know the evict-before-commit window exists and can name the fix

---

## What Phase 6 does next

Event-service is complete and fast. Time for the service that has to **talk** to it.

**Booking Service + OpenFeign:** synchronous service-to-service calls, how Feign turns an interface into
HTTP, connect vs read timeouts, error decoding, propagating the correlation id and user identity across
the hop, and — most importantly — the failure modes that make synchronous calls dangerous.

**Pre-reading for Phase 6:** synchronous vs asynchronous calls, and what a socket timeout actually is.
