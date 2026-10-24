# Phase 5 — Redis caching: cache-aside, TTL, invalidation, and what breaks

**Time:** 4–5 hours
**Needs:** Phase 4 finished (event-service serving events).
**Pre-reading:** cache hit vs miss, TTL, and why cache invalidation is famously hard.

---

## In plain English

This phase makes repeated event reads faster by keeping temporary copies in Redis. PostgreSQL remains the place where the real data is saved.

For example, many people may open the same concert page. After the first database read, later requests can reuse a Redis copy. When the event changes, remove the old copy. Seat reservation must still check the real database count, because a cached count may be old.

Words used in this phase:

- **Cache:** a temporary copy kept so repeated reads take less work.
- **Key:** the name used to find a cached value, such as an event id.
- **Cache hit / miss:** the copy was found / the copy was not found.
- **TTL (time to live):** how long a cached copy may stay before it expires.
- **Invalidation:** removing an old copy after the real data changes.
- **Eviction:** removing a cache entry. Redis also uses this word for removing entries when memory is full.
- **Stale data:** a cached copy that no longer matches the latest saved data.
- **Serialization:** turning a Java object into data that can be stored, such as JSON. Deserialization turns it back.
- **AOP proxy:** a wrapper Spring puts around an object to add work such as caching or transaction handling.

Start with the cache hit/miss flow in A2. Then ask, "When does this copy become too old, and what removes it?"

---

# PART A — Understand the ideas

## A1. Why cache at all

`GET /api/v1/events/1` currently reaches the controller, loads the row from Postgres through Hibernate and JDBC, builds the response object, and converts it to JSON. A database read may take roughly **5–20 ms** in this example.

Reading the same data from Redis can be quicker, roughly **0.2–1 ms** in this example. Redis keeps data in memory and can return it without running the database query again. Actual times depend on your setup.

Rough numbers worth carrying in your head:

| Operation | Time |
|---|---|
| L1 CPU cache | ~1 ns |
| RAM | ~100 ns |
| Redis on the same network | ~200 µs |
| Postgres simple indexed query | ~1–5 ms |
| Postgres complex join | ~10–100 ms |
| Disk seek (spinning) | ~10 ms |

**Caching also reduces database work.** If 1000 people request the same unchanged event each second, the database repeats the same query. A cached copy lets many of those requests reuse one result, leaving database connections available for writes.

### When caching is right, and when it is a trap

**Good candidates:** read far more often than written; the same values requested repeatedly; slightly
out of date data is acceptable; expensive to compute.

**Bad candidates:** written as often as read; every request wants different data (no reuse); **out of date
data is dangerous**.

**Our decisions in this service:**

| Data | Cache? | Why |
|---|---|---|
| Event details (title, venue, date) | **yes** | Read constantly, changed rarely |
| Published events list (page 1) | **yes, short TTL** | Hot, mostly stable |
| `availableSeats` | **no — read from the database** | Changes on every booking; out of date here means double booking |

**Cached seat counts are only for display.** A count may change after the copy is cached. The reservation path must check the live database row before accepting seats. A screen showing 2 available seats is not a promise that both are still available.

Know why you choose to cache some data and leave other data uncached. Faster reads are useful only when an older answer is acceptable.

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

**Benefits:** the cache contains data people actually requested. If Redis fails, the application can read from the database instead, provided we add the error handling shown later.

**Limits:** a cache miss still needs the slower database read. Also, a cached copy may briefly remain old after the database changes.

### The others, briefly

- **Read-through:** the cache system loads missing data from the database. This moves loading code out of the application, but the cache system must understand the data source.
- **Write-through:** the write path updates the cache and database before returning. This adds waiting and still needs clear failure handling for both writes.
- **Write-behind / write-back:** save to the cache first and write to the database later. It can be fast, but a cache failure before the database save can lose data.

**Cache-aside is the default for a reason.** Spring's `@Cacheable` implements exactly this pattern.

### Why delete on write, never update

Both look reasonable:

```java
cache.put(key, newValue);   // update
cache.evict(key);           // delete
```

**Deleting the old entry is usually easier to manage than updating two copies.** With two requests changing the same record at once:

```
Writer A: DB write v1 ──────────▶ cache.put(v1)
Writer B:      DB write v2 ─▶ cache.put(v2)
                                       ▲
   final DB state = v2, final cache state = v1.  Permanently wrong.
```

Database and cache changes are separate operations, so they can finish in different orders. Deleting the cached entry lets a later read reload the database value. There can still be timing gaps, especially around transaction commit; Step 6 explains them.

## A3. TTL and eviction

**TTL (time to live)** is the time before a cache entry expires. If an old entry was not removed correctly, expiry eventually forces a fresh load. A shorter TTL limits how long old data may be shown.

Picking one is a business question, not a technical one: *how out of date may this be?*

| Data | TTL | Reasoning |
|---|---|---|
| Event details | 10 min | Changes rarely; 10 minutes of how out of date the data can be is harmless |
| Event list | 60 s | New events should appear quickly |
| Seat counts | **not cached** | Any how out of date the data can be is a correctness bug |

Redis also removes entries when it reaches `maxmemory`. Its **eviction policy** decides which entries to remove. `allkeys-lru` removes entries not used recently; `volatile-ttl` favours entries nearest expiry; `noeviction` refuses new writes instead of removing entries.

**Choose a memory limit and eviction policy.** If Redis becomes full and refuses cache writes, those failures must not unexpectedly break ordinary requests.

## A4. The four ways caches go wrong

### 1. Stale data

The database changes, but a cached copy still shows the old value. This is **stale data**. Short TTLs and removing entries on writes reduce how long it lasts, but separate database and cache operations can still leave timing gaps.

### 2. Cache stampede (thundering herd)

A popular key expires. In that instant 500 happening at the same time requests all miss, and all 500 hit the database
for the same row.

```
      TTL expires
          │
 500 req ─┼──▶ all miss ──▶ 500 identical queries ──▶ database melts
```

Ways to reduce the problem: let only one request reload a value, refresh it before expiry, or add a small random amount to expiry times. This random variation is called **jitter**. It helps entries written together expire at different times.

### 3. Cache penetration

Requests for a key that **does not exist anywhere**. Every one misses the cache and hits the database.
`GET /api/v1/events/999999999` in a loop is a simple denial-of-service.

Fix: **cache the "not found" answer too**, with a short TTL. Spring does this if you allow null caching
— but be careful, because now a newly created id may return "not found" from the cache for a few
seconds.

### 4. Cache avalanche

If many entries expire together, many requests suddenly reach the database. This is a **cache avalanche**. Spread expiry times with jitter and preload important entries where useful.

## A5. Redis facts that change how you use it

**Redis executes ordinary commands one at a time.** Commands such as `INCR` and `SETNX` complete as a single operation. However, a sequence of separate commands from your application can still have gaps between them. Phase 8 uses this distinction for locks.

It also means **one slow command blocks everything**. `KEYS *` on a million keys is a production
incident. Use `SCAN`.

**Persistence:** RDB (periodic snapshots — fast, loses recent writes) and AOF (append-only log — safer,
slower). For a pure cache you can turn both off; the data is reconstructible by definition. We left AOF
on in Phase 1 because Phase 8 stores locks here.

**Data types** worth knowing: `String` (any bytes — our JSON), `Hash` (field/value maps), `List`, `Set`,
`Sorted Set` (leaderboards, delayed queues), plus TTL on any key.

**Local cache:** each application instance keeps its own copy, avoiding a network call. **Shared Redis cache:** instances read the same copy, so removing an entry affects them all. Separate local caches need a way to learn about changes or they may show different old values.

## A6. Serialization — the trap nobody warns you about

**Serialization converts an object into stored data.** Spring's default JDK format is hard to read in `redis-cli`, needs `implements Serializable`, and can cause compatibility problems when classes change. We use JSON for these cached responses.

**Use JSON.** Readable, language-neutral, tolerant of added fields.

**Consider older cached JSON when changing a DTO.** A missing field normally becomes null or a default value. An extra field can cause a read error unless Jackson is configured to ignore unknown fields. Versioned cache keys help avoid mixing incompatible formats.

**Rules:**
1. **Cache DTOs rather than JPA entities.** Entities can contain framework wrappers and unloaded relationships that are difficult to store as JSON. A restored entity is no longer tracked by its old Hibernate session, and saving it can overwrite newer data.
2. Configure Jackson to ignore extra fields it does not recognise.
3. **Version the cache-key prefix**, for example `events:v1:1`. If the format changes incompatibly, use `v2` and let old entries expire.

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
wait with no time limit — the cache, which exists to make things faster, becomes the reason nothing responds.

**A cache is meant to save waiting time.** Choose a short Redis timeout so a slow cache does not delay a request much longer than the database read it was meant to avoid. The example uses 2 seconds; some systems need a much shorter limit.

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

`@EnableCaching` turns on Spring's caching wrappers, called **AOP proxies**. Without it, `@Cacheable` does not activate caching. **A call from one method to another in the same object skips that wrapper**, so it also skips the caching check. This is called the self-invocation problem.

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

**`disableCachingNullValues()` means missing values are not cached.** Our service throws `EventNotFoundException` for a missing event, so it does not return null to cache. Repeated `GET /events/999` calls therefore keep reaching the database. If you later return `Optional.empty()` and cache missing results, give them a short TTL so newly created data is not hidden for long.

**`prefixCacheNameWith("ebp:v1:")` gives keys an application name and a version.** `ebp:` keeps this application's keys separate from others. If a DTO changes incompatibly, change `v1:` to `v2:` so the application reads new entries while the old ones expire.

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

Without an error handler, Redis failure can make the request return 500. The handler lets the request use the database instead. Reads are slower while Redis is unavailable, but the cache does not prevent the application from doing its basic job.

**The log level shows the kind of problem.** A failed GET or PUT usually loses speed, so it is logged as `warn`. A failed EVICT may leave old data visible after a database update, so it is logged as `error`.

**Limit:** this error handler keeps requests running, but cannot remove an old entry while Redis is unreachable. The TTL limits how long that entry can stay once it is served again.

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

`key = "#id"` tells Spring to use the method's id parameter as the cache key. The expression language is called **SpEL**. Without a chosen key, Spring combines the parameters, which may be unsuitable for complex objects.

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

**This key includes page number and size only.** It does not include sort settings, so the cached result is correct only for the default sort. If you later allow different sorting, include it in the key too. Otherwise two different queries may share the wrong result.

**The list uses a 60-second TTL because time changes the answer.** It includes only events after `Instant.now()`. An event can start even when nobody updates a row, so a long-lived copy could keep showing it as upcoming.

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

**`allEntries = true` clears every cached page of the list.** Changing or publishing an event can move items between pages, so clearing one page is not enough. Later requests rebuild the pages they need. This is simple, but increases database work after changes.

**Now the part most people get wrong.** `@CacheEvict` runs when the method **returns**, and by default
that is *before* the transaction commits.

```
method body runs
  ↓
cache evicted           ← here
  ↓
transaction commits     ← database updated here
```

A read can arrive after the cache entry is removed but before the database transaction commits. It may load the old database value and cache it again. That old copy can remain until expiry unless another removal happens.

Setting `beforeInvocation = false` (the default) does not fix it; the ordering is the AOP proxy
ordering, and the transaction proxy is on the outside. The robust fixes, in order of effort:

1. Accept it — the window is milliseconds and TTL bounds the damage. **This is our choice.**
2. Evict again *after* commit, using `TransactionSynchronizationManager.registerSynchronization(...)`
   with an `afterCommit` callback.
3. Publish a domain event on commit (Phase 11's outbox) and evict from its handler.

**Know that this window exists and can name the fix.** That is a really senior-level detail, and
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

**The cache did not learn about the direct database change.** A script or another write path must also remove affected cache entries, or users can keep seeing old values until expiry. Prefer one controlled path for changes.

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

Repeated missing-id requests can still overload the database. Options include briefly caching the missing result, checking id format before the query, or using a **Bloom filter**, a space-efficient check that can rule out many nonexistent ids. Understand this limit before copying `disableCachingNullValues()` into another service.

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
| `@Cacheable` called from the same class | Cache without a clear error skipped | AOP self-invocation — call through another bean |
| Caching entities instead of DTOs | Proxy/lazy errors; detached-entity writes | Cache DTOs |
| JDK serialization | Unreadable keys, breaks on class change | JSON |
| No `CacheErrorHandler` | Redis outage becomes a service outage | Log and fall through |
| No timeout on the Redis client | A hung Redis hangs every request | 2 s or less |
| `@CachePut` on write | Lost-update race leaves a wrong value cached | `@CacheEvict` |
| Caching volatile data (seat counts) | Overselling | Do not cache inventory |
| One TTL for everything | Something is always wrong | Per-cache TTLs |
| Implicit cache keys | Never hits, or without a limit key space | Explicit `key = "#id"` |
| `KEYS *` in production | Blocks single-threaded Redis | `SCAN` |
| `maxmemory-policy noeviction` | Writes rejected when full | `allkeys-lru` for a cache |
| Writing to the database outside the app | Permanent how out of date the data can be | One writer, or explicit invalidation |

---

## Notes for a real running system

- **Set `maxmemory` and an eviction policy such as `allkeys-lru`.** Redis should not keep growing until the operating system stops it for using too much memory.
- **Spread expiry times with TTL jitter**, such as `ttl + random(0..10%)`, so entries written together do not all expire together.
- **Measure cache hits and misses** using `redis-cli INFO stats`, including `keyspace_hits` and `keyspace_misses`. A low hit rate means many requests still use the database; check whether caching is helping.
- **Consider Redis Sentinel or Cluster** for recovery from a failed Redis instance. Keep the database fallback even with these tools.
- **Be careful with cached permissions and tokens.** Old permission data can allow access that should have been removed. Use suitably short lifetimes and a clear invalidation plan.
- **Keep the database as the saved source.** Losing an ordinary cache should not lose business records.
- **Preload important entries at startup** if many initial cache misses would overload the database. This is called warming the cache.
- **Remember that `@Cacheable` skips the method body on a hit.** Logging, audit writes, or other work inside that method will also be skipped.

---

## Phase 5 checklist

- [ ] Second call to the same event is measurably faster
- [ ] `redis-cli MONITOR` shows GET/SET with your `ebp:v1:` prefix
- [ ] Cached values are readable JSON with a TTL counting down
- [ ] Updating an event evicts its key
- [ ] Redis down → service still serves reads (you removed the error handler and saw the 500)
- [ ] You changed the database directly and watched the cache serve out of date data for minutes
- [ ] You reproduced cache penetration with a non-existent id
- [ ] You reproduced a stampede with 50 parallel requests
- [ ] You can explain why we do **not** cache `availableSeats`
- [ ] You can explain why eviction beats update, with the interleaving that proves it
- [ ] You know the evict-before-commit window exists and can name the fix

---

## What Phase 6 does next

Event-service is complete and fast. Time for the service that has to **talk** to it.

**Next, Booking Service + OpenFeign:** create bookings and make HTTP calls to event-service. You will set waiting limits, handle failed responses, and forward the user details and request id. You will also see what happens when one service succeeds and the other fails.

**Pre-reading for Phase 6:** synchronous vs asynchronous calls, and what a socket timeout actually is.
