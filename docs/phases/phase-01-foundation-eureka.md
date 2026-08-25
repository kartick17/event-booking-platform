# Phase 1 — Foundation: Monorepo, Local Infrastructure, Service Discovery

**Time:** 3–4 hours
**Before you start, read:** roadmap §7 row for Phase 1.

---

## What you will have at the end of this phase

1. A Maven monorepo where `mvn clean install` at the root builds every service.
2. PostgreSQL, Redis and Kafka running locally through Docker Compose.
3. A Eureka server running at `http://localhost:8761` with a dashboard you can open.
4. An `auth-service` skeleton that registers itself in Eureka and answers `GET /ping`.
5. A clear mental model of what "service discovery" really means.

No business logic yet. This phase is the ground everything else stands on.

---

# PART A — Concepts

Read this part before typing anything. It is 20 minutes and it makes the rest obvious.

## A1. Monorepo and Maven multi-module

### What is a monorepo

All services live in **one Git repository**. The opposite is polyrepo — one repository per service.

| | Monorepo | Polyrepo |
|---|---|---|
| Change 3 services in one feature | one commit, one PR | 3 PRs, must be merged in order |
| Shared config / build rules | one place | copy-pasted everywhere |
| Onboarding a new dev | clone once | clone 8 repos |
| Independent deploy | possible, needs care | natural |
| Risk | easy to accidentally couple services | harder to see the whole system |

We use a monorepo because you are one person learning. Real companies use both. Netflix and Uber use polyrepo; Google and Meta use one giant monorepo.

**The important part:** monorepo is about *where the code sits*, not about *how it runs*. Each service still builds its own jar, has its own database, and can be deployed alone. If you ever find yourself unable to deploy one service without the others, the monorepo has turned into a **distributed monolith** — the worst of both worlds.

### What Maven multi-module means

A normal Maven project produces one jar. A **multi-module** project has a *parent* POM that produces nothing itself and instead lists child modules.

```
pom.xml              <- parent, packaging = pom, builds nothing
├── discovery/service-discovery/pom.xml   -> service-discovery.jar
├── gateway/api-gateway/pom.xml           -> api-gateway.jar
└── services/auth-service/pom.xml         -> auth-service.jar
```

When you run `mvn clean install` in the root, Maven starts the **reactor**: it reads all modules, works out the order they depend on each other, and builds them one by one.

### Two POM ideas you must not mix up

**`<dependencies>`** — "this project actually uses these libraries." They end up on the classpath.

**`<dependencyManagement>`** — "IF any module asks for this library, use this version." It puts nothing on the classpath. It only pins versions.

This is why a child module can write:

```xml
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

with **no `<version>`**. The version comes from the parent's `dependencyManagement`. One place to change it, five modules follow.

### What a BOM is

**BOM = Bill of Materials.** A special POM that contains only `dependencyManagement` — a giant list of "these 300 libraries are tested to work together at these versions."

Spring Cloud ships one. You import it, and every Spring Cloud library you use afterwards gets a version that is known to work with your Spring Boot version. Without it you will hit `NoSuchMethodError` at runtime, which is the worst kind of bug: it compiles fine.

> **Interview line:** "`dependencyManagement` pins versions without adding them to the classpath. A BOM is a POM made only of `dependencyManagement`, imported with `scope=import`, so one line pins a whole ecosystem."

### Version compatibility — do not skip this

Spring Boot and Spring Cloud versions are **paired**. A wrong pair fails at startup with confusing errors.

| Spring Boot | Spring Cloud |
|---|---|
| 3.2.x | 2023.0.x |
| 3.3.x | 2023.0.x |
| 3.4.x | 2024.0.x |
| 3.5.x | 2025.0.x |

We pin **Spring Boot 3.3.4 + Spring Cloud 2023.0.3 + Java 21**. That pair is stable and every tutorial you find will match it. If you want a newer pair, check the official matrix at `spring.io/projects/spring-cloud` first and change both numbers together, never one.

---

## A2. Service discovery — the real problem

### The problem, concretely

Booking Service needs event data. You write:

```java
restClient.get().uri("http://localhost:8082/api/v1/events/42")
```

This works on your laptop. Then reality arrives:

1. **You run 3 copies of event-service** for load. Which port? You now need a load balancer and a fixed address for it.
2. **A copy crashes and restarts on a different port.** Your hardcoded URL points at nothing.
3. **You deploy to a server.** `localhost` is now wrong in every service.
4. **You add a staging environment.** Now every URL exists twice in config.
5. **Autoscaling adds a 4th instance at 3am.** Nobody knows its address.

The addresses of services are **dynamic**. Configuration files are **static**. That gap is the problem.

### The fix: a registry

A **service registry** is a small database of "who is alive and where."

```
             ┌───────────────────────────────┐
             │        Eureka Server          │
             │                               │
             │  EVENT-SERVICE                │
             │    192.168.1.5:8082  UP       │
             │    192.168.1.5:8092  UP       │
             │  AUTH-SERVICE                 │
             │    192.168.1.5:8081  UP       │
             └───────────────────────────────┘
                   ▲                  │
      1. register  │                  │  3. "give me the list"
      2. heartbeat │                  ▼
             ┌─────┴──────┐    ┌─────────────┐
             │event-service│    │booking-svc │
             └────────────┘    └─────────────┘
```

Now booking-service says `http://event-service/api/v1/events/42` — a **logical name**, not an address. Something turns that name into a real IP at call time.

### The three moving parts

**1. Registration.** On startup, the Eureka *client* inside each service POSTs its own details to the Eureka server: application name, IP, port, health URL, a status of `UP`, and an `instanceId`.

**2. Heartbeat (lease renewal).** Every **30 seconds** the client PUTs a heartbeat. Think of registration as signing a rental lease and the heartbeat as paying rent. Miss the rent for **90 seconds** and the lease expires — the server marks the instance for removal.

**3. Lookup (registry fetch).** Every **30 seconds** each client downloads the whole registry and keeps it in memory. This is the part most people miss: **clients do not ask Eureka on every request.** They ask their own local copy. That is why calls stay fast, and why Eureka being down does not immediately break anything.

### Client-side vs server-side load balancing

**Server-side** (classic): everything goes through one nginx / AWS ALB. The load balancer knows the instance list. One extra network hop. One more thing to keep alive.

```
booking ──▶ load balancer ──▶ event instance 2
```

**Client-side** (what we use): booking-service already *has* the instance list from Eureka. It picks one itself — round robin by default — and calls it directly. No extra hop.

```
booking (picks instance 2 itself) ──▶ event instance 2
```

Spring Cloud LoadBalancer does the picking. When you write `lb://event-service` in the gateway, `lb` literally means "load balance this name using the registry."

### The timing table — memorise this

These defaults explain almost every "why is my service not showing up" question.

| What | Default | Property |
|---|---|---|
| Heartbeat interval | 30 s | `eureka.instance.lease-renewal-interval-in-seconds` |
| Lease expiry | 90 s | `eureka.instance.lease-expiration-duration-in-seconds` |
| Client fetches registry | 30 s | `eureka.client.registry-fetch-interval-seconds` |
| Server eviction task runs | 60 s | `eureka.server.eviction-interval-timer-in-ms` |
| Server response cache refresh | 30 s | `eureka.server.response-cache-update-interval-ms` |

Add them up: a **dead** instance can stay in another service's list for up to **90 + 60 + 30 + 30 ≈ 3.5 minutes**.

That number is not a bug. It is a deliberate trade. Eureka chose **availability over consistency** (the AP side of CAP): it would rather hand you a slightly stale list than refuse to answer. The cost is that *your calling code must survive calling a dead instance*. That is exactly why Phase 7 (Resilience4j retry + circuit breaker) exists. Discovery and resilience are two halves of one idea.

### Eureka's internal caches

The server keeps three layers:

```
registry (real data)
   │  copied on write
   ▼
readWriteCacheMap        (invalidated immediately on register/cancel)
   │  copied every 30 s
   ▼
readOnlyCacheMap         <- this is what clients actually read
```

So even on the server side, a fresh registration can take up to 30 seconds to be visible to clients. In development we shrink these numbers so feedback is fast. **In production you leave the defaults**, because low intervals × many instances = a lot of traffic hitting the registry.

### Self-preservation mode

If Eureka suddenly stops receiving heartbeats from **more than 15%** of instances in 15 minutes, it assumes *the network is broken, not the services*, and it **stops evicting anything**.

Reasoning: a network split is more likely than every service dying at once. Wrongly keeping a dead instance is recoverable — the caller retries. Wrongly deleting every healthy instance takes the whole system down.

On your laptop this is annoying: you stop a service, and it stays listed as `UP` forever. So we turn it off **in dev only** and leave it on in production.

### When you should NOT use Eureka

- **You are on Kubernetes.** K8s has a built-in registry: a `Service` object gives you a stable DNS name and load balancing. Running Eureka there duplicates it.
- **You have 2–3 services with fixed addresses that never change.** Config is simpler.
- **You need strong consistency of the registry.** Eureka is AP. Consul and etcd are CP — they would rather return an error than stale data.

We use it because it is the clearest way to *see* registration, heartbeats and eviction happening, and because plenty of real Spring shops still run it.

---

## A3. Local infrastructure with Docker Compose

We run **infrastructure in Docker** (Postgres, Redis, Kafka) and **our services from the IDE**.

Why not dockerise our services too? Because you want breakpoints, hot reload, and stack traces in your IDE. Docker for services is a deployment concern, and you said no deployment topics yet.

### One Postgres container, three databases

Real production: three separate database servers, or at least three separate instances. Locally, one container with three databases inside is enough — the rule that matters is **no service reads another service's tables**, and that rule is enforced by us, not by the container count.

### Kafka in KRaft mode

Old Kafka needed **ZooKeeper**, a second cluster just to store metadata. Modern Kafka (3.3+) uses **KRaft** — Kafka manages its own metadata. One container instead of two. We use KRaft.

### The two-listener trick (this confuses everyone)

Kafka tells clients where to reach it. That address must be correct *from the client's point of view*.

- A client **inside** the Docker network reaches it at `kafka:9092`.
- A client **on your host** (your Spring app in the IDE) reaches it at `localhost:29092`.

One address cannot be both. So Kafka gets **two listeners** and advertises the right one on each. Our Spring apps run on the host, so they will use `localhost:29092`.

---

# PART B — Build it

Now we type. Work top to bottom. Do not skip the run/verify checkpoints.

---

## Step 1 — Folder skeleton

```bash
cd /home/weloin/Documents/event-booking-platform

mkdir -p services/auth-service
mkdir -p services/event-service
mkdir -p services/booking-service
mkdir -p gateway/api-gateway
mkdir -p discovery/service-discovery
mkdir -p infrastructure/init
```

Only `discovery/service-discovery` and `services/auth-service` get filled this phase. The rest stay empty on purpose — the shape of the system should be visible from `ls` on day one.

---

## Step 2 — Root POM, piece by piece

**File:** `pom.xml` (repo root)

### 2a. The shell and the parent

Start with just this:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.4</version>
        <relativePath/>
    </parent>

    <groupId>com.eventbooking</groupId>
    <artifactId>event-booking-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>event-booking-platform</name>

</project>
```

**Why each part:**

- `<parent>` is `spring-boot-starter-parent`. It gives us the version of every Spring library, sensible plugin config, and UTF-8 defaults — for free.
- `<relativePath/>` empty means "do not look for this parent on disk, download it." Without it Maven searches `../pom.xml` and warns.
- `<packaging>pom</packaging>` is the line that makes this a parent. It produces **no jar**. Its only job is to hold config and list modules.
- `1.0.0-SNAPSHOT`: `SNAPSHOT` means "in development, may change." Maven treats snapshot versions specially — it re-checks them instead of caching forever.

### 2b. Properties

Add inside `<project>`:

```xml
    <properties>
        <java.version>21</java.version>
        <spring-cloud.version>2023.0.3</spring-cloud.version>
    </properties>
```

`java.version` is a property that `spring-boot-starter-parent` already knows about — setting it configures the compiler. `spring-cloud.version` is ours; we use it in the next block. Two numbers, one place.

### 2c. Modules

```xml
    <modules>
        <module>discovery/service-discovery</module>
        <module>services/auth-service</module>
    </modules>
```

Only what exists. Maven fails the build if a listed module folder has no POM. We add the others as we create them.

### 2d. The Spring Cloud BOM

```xml
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
```

`<type>pom</type>` + `<scope>import</scope>` together mean: "do not add this as a library — open it up and copy all its version pins into mine." That is the BOM import. After this, no child module writes a Spring Cloud version again.

### 2e. Checkpoint

```bash
mvn -q validate
```

It will fail — the two module folders have no POM yet. That is the expected error right now:

```
Child module .../discovery/service-discovery does not exist
```

Good. It proves the reactor is reading your `<modules>` list.

---

## Step 3 — Infrastructure

### 3a. Postgres

**File:** `infrastructure/docker-compose.yml`

```yaml
services:

  postgres:
    image: postgres:16-alpine
    container_name: ebp-postgres
    environment:
      POSTGRES_USER: ebp
      POSTGRES_PASSWORD: ebp_password
      POSTGRES_DB: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./init/01-create-databases.sql:/docker-entrypoint-initdb.d/01-create-databases.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ebp"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  postgres_data:
```

**Why each part:**

- `alpine` image: small, fast to pull.
- `"5432:5432"` = `host:container`. Left side is what you type in your app config.
- The named volume `postgres_data` keeps your data when the container restarts. Delete it with `docker compose down -v` when you want a clean slate.
- Anything mounted into `/docker-entrypoint-initdb.d/` runs **once**, the first time the data directory is empty. If you edit the SQL later it will *not* re-run — you must `down -v` first. This trips up everyone once.
- `healthcheck` lets other containers wait for Postgres to be genuinely ready, not just started.

### 3b. The database creation script

**File:** `infrastructure/init/01-create-databases.sql`

```sql
CREATE DATABASE auth_db;
CREATE DATABASE event_db;
CREATE DATABASE booking_db;
```

Three databases, one per service. This file is the physical shape of "database per service."

Later, when we add Flyway, each service will create its **own tables inside its own database**. No service gets credentials for another's database. That is the boundary — and it is a boundary you must keep by discipline, because nothing here technically stops you from breaking it.

### 3c. Start it and verify

```bash
cd infrastructure
docker compose up -d postgres
docker compose ps
```

Expect `ebp-postgres` with status `healthy` (wait ~10 s). Now prove the databases exist:

```bash
docker exec -it ebp-postgres psql -U ebp -d postgres -c "\l"
```

You should see `auth_db`, `booking_db`, `event_db` in the list. If not, the init script did not run — `docker compose down -v` and start again.

### 3d. Redis

Add to `docker-compose.yml`, inside `services:`:

```yaml
  redis:
    image: redis:7-alpine
    container_name: ebp-redis
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5
```

And add `redis_data:` under the `volumes:` block at the bottom.

`--appendonly yes` turns on Redis persistence (writes an append-only log to disk). For a cache you often do not need it. We enable it now because in Phase 8 Redis holds distributed locks, and losing those on a restart is worth understanding rather than hiding.

Verify:

```bash
docker compose up -d redis
docker exec -it ebp-redis redis-cli ping
```

Expect `PONG`.

### 3e. Kafka

Add to `services:`:

```yaml
  kafka:
    image: apache/kafka:3.7.0
    container_name: ebp-kafka
    ports:
      - "29092:29092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,PLAINTEXT_HOST://0.0.0.0:29092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
```

**Why each part — you will meet all of these again in Phase 10:**

- `KAFKA_PROCESS_ROLES: broker,controller` — one container plays both roles. That is KRaft mode. Real clusters split them.
- Three listeners: `PLAINTEXT` for container-to-container, `CONTROLLER` for KRaft's internal metadata traffic, `PLAINTEXT_HOST` for you.
- `KAFKA_ADVERTISED_LISTENERS` is the important one. This is the address Kafka **hands back to clients** after they connect. Get it wrong and your app connects once, then times out trying to reach the address it was given. Your Spring apps will use `localhost:29092`.
- Replication factor `1` everywhere: we have one broker, so nothing can be replicated. In production these are `3`, and `MIN_ISR` (minimum in-sync replicas) is `2` — meaning a write must reach 2 replicas before it counts. Phase 10 covers this properly.
- `GROUP_INITIAL_REBALANCE_DELAY_MS: 0` — normally Kafka waits 3 seconds before rebalancing a consumer group so late joiners are included. In dev you want instant.

Verify:

```bash
docker compose up -d kafka
docker exec -it ebp-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

Empty output = success. No error means the broker is up and answering.

**Full stack up:**

```bash
docker compose up -d
docker compose ps
```

All three healthy. Infrastructure done.

---

## Step 4 — Eureka server

### 4a. Its POM

**File:** `discovery/service-discovery/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.eventbooking</groupId>
        <artifactId>event-booking-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>service-discovery</artifactId>
    <name>service-discovery</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

**The three things to notice:**

1. `<parent>` points **up** at our root POM, with a real `relativePath`. This is what makes it a child module. Parent→child in `<modules>`, child→parent in `<parent>`. Both directions must exist.
2. The Eureka dependency has **no `<version>`**. It comes from the Spring Cloud BOM you imported in step 2d. This is that BOM doing its job.
3. `spring-boot-maven-plugin` — without it you get a plain jar with no `main` manifest and no dependencies inside, and `java -jar` fails. With it you get an **executable fat jar**: your classes plus every dependency, plus a launcher that knows how to load them.

Note there is **no `<groupId>` and no `<version>`** on this module — both are inherited from the parent. Less to keep in sync.

### 4b. The main class

**File:** `discovery/service-discovery/src/main/java/com/eventbooking/discovery/ServiceDiscoveryApplication.java`

```java
package com.eventbooking.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

@SpringBootApplication
@EnableEurekaServer
public class ServiceDiscoveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServiceDiscoveryApplication.class, args);
    }
}
```

**What the two annotations actually do:**

`@SpringBootApplication` is three annotations in one:
- `@Configuration` — this class can define beans.
- `@EnableAutoConfiguration` — scan every jar on the classpath for auto-configuration classes and apply the ones whose conditions match. This is why adding a dependency changes behaviour with no code.
- `@ComponentScan` — scan **this package and everything below it** for `@Component`, `@Service`, `@Repository`, `@Controller`. This is why your main class must sit in the **topmost package**. Put it in `com.eventbooking.discovery.config` by mistake and your controllers in `com.eventbooking.discovery.api` are invisible. Very common bug.

`@EnableEurekaServer` imports a marker bean. Eureka's auto-configuration is written as `@ConditionalOnBean(EurekaServerMarkerConfiguration.Marker.class)` — so the whole Eureka server only wires itself up when that marker exists. That is the standard Spring `@EnableXxx` pattern: the annotation is a switch, the real work is in auto-configuration.

### 4c. Configuration

**File:** `discovery/service-discovery/src/main/resources/application.yml`

```yaml
server:
  port: 8761

spring:
  application:
    name: service-discovery

eureka:
  instance:
    hostname: localhost
  client:
    register-with-eureka: false
    fetch-registry: false
    service-url:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/
```

**Line by line, because every one of these matters:**

- `8761` is Eureka's conventional port. Not a rule, but every Spring developer expects it there.
- `spring.application.name` is **the identity of a service**. Eureka uses it as the registry key, the gateway routes by it, Feign looks it up by it. Forget it and your service registers as `UNKNOWN`.
- `register-with-eureka: false` — the Eureka server is itself a Eureka client by default. With one server, registering with itself is noise. In a production cluster of 2–3 Eureka servers you set this to `true` so they replicate to each other.
- `fetch-registry: false` — same reason: don't download a registry from yourself.
- `defaultZone` is the address clients use. "Zone" is Eureka's word for an availability zone (an AWS idea). One zone here.

### 4d. Development-only tuning

Add under `eureka:`:

```yaml
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
    response-cache-update-interval-ms: 5000
```

This is the dev-only block from concept A2. Self-preservation off means killed services actually disappear. Eviction every 5 s and cache refresh every 5 s mean you see changes in seconds instead of minutes.

**Write a comment above it in the file: `# DEV ONLY — never ship these values.`** In production, self-preservation on and default intervals, or a network hiccup will empty your registry and take the system down.

### 4e. Run it

```bash
cd /home/weloin/Documents/event-booking-platform
mvn -q clean install -DskipTests
java -jar discovery/service-discovery/target/service-discovery-1.0.0-SNAPSHOT.jar
```

Or just run `ServiceDiscoveryApplication` from your IDE.

Open **http://localhost:8761**.

You should see the Eureka dashboard. Look at **"Instances currently registered with Eureka"** — the table is empty. Correct: nothing has registered yet.

You will also see a red banner:

> THE SELF PRESERVATION MODE IS TURNED OFF. THIS MAY NOT PROTECT INSTANCE EXPIRY IN CASE OF NETWORK/OTHER PROBLEMS.

That is your `enable-self-preservation: false` talking. Expected in dev.

---

## Step 5 — First client: auth-service skeleton

The goal here is **not** the auth logic. It is watching a service appear in the registry. Phase 2 builds the real thing on top of this skeleton.

### 5a. Its POM

**File:** `services/auth-service/pom.xml`

Same shape as the discovery POM, with a different artifactId and different dependencies:

```xml
    <artifactId>auth-service</artifactId>
    <name>auth-service</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
    </dependencies>
```

(Keep the same `<parent>`, `<modelVersion>` and `<build>` blocks as before — copy them from the discovery POM and change `relativePath` to `../../pom.xml`, which is the same depth.)

**Why these three:**

- `starter-web` — Spring MVC plus embedded Tomcat. Gives us a web server and `@RestController`.
- `starter-actuator` — exposes `/actuator/health`. Eureka points its health check at this. Without it, "is this instance healthy" has no answer.
- `eureka-client` — the registration + heartbeat + registry-fetch machinery. Note `-client`, not `-server`. Mixing these up is a classic first-day mistake.

### 5b. Add the module to the root POM

In the root `pom.xml`, `<modules>` should now be:

```xml
    <modules>
        <module>discovery/service-discovery</module>
        <module>services/auth-service</module>
    </modules>
```

Both exist now, so `mvn -q validate` at the root should pass silently.

### 5c. Main class

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/AuthServiceApplication.java`

```java
package com.eventbooking.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

**No `@EnableEurekaClient`.** You will see that annotation in older tutorials. Since Spring Cloud 2020 it is unnecessary and deprecated: if `spring-cloud-starter-netflix-eureka-client` is on the classpath and `eureka.client.enabled` is not `false`, registration happens automatically. Auto-configuration by classpath presence — the modern Spring way.

### 5d. Configuration

**File:** `services/auth-service/src/main/resources/application.yml`

```yaml
server:
  port: 8081

spring:
  application:
    name: auth-service

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

**The two lines under `eureka.instance` are the ones worth understanding:**

`prefer-ip-address: true` — by default a service registers under its **hostname**. On a laptop that is something like `weloin-thinkpad`, which other machines cannot resolve. Registering the IP avoids a whole class of "works on my machine, DNS fails in the cluster" problems.

`instance-id: ${spring.application.name}:${server.port}` — the unique key for **this instance**. The default includes the hostname, so if you start two copies on the same machine they can collide and overwrite each other in the registry. Making the port part of the id keeps them separate. Try it later: run auth-service on 8081 and 8091 and watch two instances appear under one application name.

`management.endpoints.web.exposure.include` — actuator hides most endpoints by default. We open only `health` and `info`. Opening everything (`*`) in production leaks heap dumps, environment variables and beans. Never do it.

### 5e. A ping endpoint

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/api/PingController.java`

```java
package com.eventbooking.auth.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/ping")
public class PingController {

    @Value("${server.port}")
    private String port;

    @GetMapping
    public Map<String, String> ping() {
        return Map.of(
                "service", "auth-service",
                "port", port,
                "status", "alive"
        );
    }
}
```

Why return the port? Because in a minute you will run two instances and call through a load balancer. Seeing the port flip between responses is how you *watch* client-side load balancing happen instead of taking my word for it.

Note the package: `com.eventbooking.auth.api`. Below `com.eventbooking.auth`, so component scanning finds it. This is the `api/` layer from the roadmap's package structure — controllers and DTOs only.

### 5f. Run and watch registration

Start `AuthServiceApplication` (Eureka must already be running).

In the auth-service log, look for these lines in order:

```
DiscoveryClient_AUTH-SERVICE/auth-service:8081: registering service...
DiscoveryClient_AUTH-SERVICE/auth-service:8081 - registration status: 204
```

`204 No Content` is Eureka saying "accepted." That is your registration.

Now refresh **http://localhost:8761**:

```
Application     AMIs        Availability Zones      Status
AUTH-SERVICE    n/a (1)     (1)                     UP (1) - auth-service:8081
```

The application name is uppercased — Eureka does that. The instance id is the one you configured.

Test the endpoint:

```bash
curl http://localhost:8081/ping
```

```json
{"service":"auth-service","port":"8081","status":"alive"}
```

### 5g. See the registry as data, not as a web page

```bash
curl -H "Accept: application/json" http://localhost:8761/eureka/apps | python3 -m json.tool
```

Read the `instance` object in the output. You will find `app`, `ipAddr`, `port`, `status`, `healthCheckUrl`, and a `leaseInfo` block with `renewalIntervalInSecs: 30` and `durationInSecs: 90`.

**This JSON is the whole of service discovery.** Every client downloads exactly this every 30 seconds, keeps it in memory, and picks an instance from it. There is no magic left after you have read this response once.

---

## Step 6 — Two instances, one name

Run a second copy on a different port:

```bash
java -jar services/auth-service/target/auth-service-1.0.0-SNAPSHOT.jar --server.port=8091
```

(`--server.port=8091` on the command line beats `application.yml`. That ordering is Spring Boot's *externalized configuration* precedence: command line > environment variables > profile yml > yml. You will use it constantly.)

Refresh the dashboard:

```
AUTH-SERVICE    n/a (2)     (2)     UP (2) - auth-service:8081 , auth-service:8091
```

**One application name, two instances.** This is the thing hardcoded URLs cannot express. In Phase 3 the gateway will route `lb://auth-service` and alternate between these two by itself.

---

## Step 7 — Break it on purpose

### Drill 1: kill an instance

Stop the 8091 copy with Ctrl-C. Watch the dashboard.

With our dev settings it disappears within about 5–15 seconds. With **production defaults** it would take up to 90 s (lease expiry) + up to 60 s (eviction task) — and during that whole window, other services would still have it in their local list and would still try to call it.

**Sit with that.** Discovery cannot prevent calls to dead instances. It never could. That is why retry and circuit breakers are not optional extras — they are the other half of the design. Phase 7.

### Drill 2: kill Eureka, keep the services

Stop the Eureka server. Leave auth-service running.

- auth-service logs a connection error every 30 s. Noisy, but it keeps serving traffic.
- `curl http://localhost:8081/ping` still works perfectly.
- Any service that had already fetched the registry still has its cached copy and can still route calls.

**Eureka being down does not take the system down.** It stops *new* information from spreading. This is the AP choice from concept A2, and it is why Eureka survives in production despite being a single logical point of coordination.

Start Eureka again. auth-service re-registers on its next heartbeat.

### Drill 3: start a service before Eureka

Stop everything. Start auth-service **first**, then Eureka.

auth-service fails its first registration attempts, logs the error, and **keeps retrying**. Once Eureka appears, it registers and everything is fine. No manual restart.

Startup order does not matter in a well-built distributed system. If a system requires you to boot services in a fixed order, that is a design smell — it usually means somebody made a startup-time call that should have been retried or deferred.

---

## Common mistakes in this phase

| Mistake | What you see | Fix |
|---|---|---|
| Forgot `spring.application.name` | Registers as `UNKNOWN` | Set the name |
| Main class in a sub-package | Controllers return 404 | Main class in the topmost package |
| `eureka-server` dependency in a service | Weird startup errors | Services use `eureka-client` |
| Edited `init.sql` after first run | New databases missing | `docker compose down -v` then up |
| Expect instant removal on kill | Stale instance for minutes | Understand the timing table; tune in dev only |
| Used `@EnableEurekaClient` | Deprecation warning | Delete it, classpath is enough |
| Two instances overwrite each other | Only one shows in dashboard | Set `instance-id` with the port |
| Copied prod-tuned intervals into prod | Registry churn under load | Dev-only block stays dev-only |

---

## Production considerations

- **Run 2–3 Eureka servers** that register with each other (`register-with-eureka: true`, `defaultZone` listing the peers). Registry replication between them is eventually consistent.
- **Secure the Eureka endpoints.** An open registry tells an attacker your whole internal topology. Put it behind auth or a private network.
- **Leave the default timings alone.** They were tuned at Netflix scale.
- **Never hardcode `defaultZone`** in the jar. It comes from an environment variable per environment. Phase 3 covers configuration management properly.
- **Health checks:** by default Eureka reports `UP` if the process is alive. Set `eureka.client.healthcheck.enabled=true` to make it report the *actuator* health instead — so a service with a dead database correctly reports `DOWN` and stops receiving traffic. We will turn this on in Phase 4, once there is a database to be unhealthy about.

---

## Phase 1 checklist

Tick every line before moving on.

- [ ] `mvn clean install` at the root builds both modules
- [ ] `docker compose ps` shows postgres, redis, kafka all healthy
- [ ] `auth_db`, `event_db`, `booking_db` all exist
- [ ] Eureka dashboard opens at :8761
- [ ] auth-service appears as `UP` in the dashboard
- [ ] `curl localhost:8081/ping` returns JSON
- [ ] You have read the raw `/eureka/apps` JSON at least once
- [ ] Two instances registered under one application name
- [ ] You killed Eureka and saw the services keep working
- [ ] You can explain, out loud: registration, heartbeat, lease expiry, registry fetch, client-side load balancing

---

## What Phase 2 does next

auth-service currently answers `/ping` and nothing else. Next we give it real users:

Spring Security's filter chain (what it is and where it sits), `UserDetailsService`, `PasswordEncoder`, JWT access and refresh tokens, roles, and a hand-written `OncePerRequestFilter`. Plus Flyway and the first real database tables.

**Pre-reading for Phase 2:** the three parts of a JWT (`header.payload.signature`), and the difference between hashing and encryption.
