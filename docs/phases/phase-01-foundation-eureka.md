# Phase 1 — Foundation: Monorepo, Local Infrastructure, Service Discovery

**Time:** 3–4 hours
**Before you start, read:** roadmap §7 row for Phase 1.

---

## What you will have at the end of this phase

1. A Maven monorepo where `mvn clean install` at the root builds every service.
2. PostgreSQL, Redis and Kafka running locally through Docker Compose.
3. A Eureka server running at `http://localhost:8761` with a dashboard you can open.
4. An `auth-service` skeleton that registers itself in Eureka and answers `GET /ping`.
5. A clear understanding of what "service discovery" really means.

No business logic yet. This phase is the ground everything else stands on.

---

## In plain English

This phase sets up the project and the tools it needs on your computer. You will not build booking features yet. First, make sure the services can start and find each other.

Picture a phone book. A service tells Eureka its name and address. Another service looks up that name when it needs to call it. If you run two copies of a service, Eureka can list both.

Words used in this phase:

- **Service:** a separate application with one main job, such as login or bookings.
- **Monorepo:** one Git repository containing several applications.
- **Maven module:** one part of the project that Maven can build.
- **Dependency:** a library your application needs. A **POM** tells Maven which libraries and settings to use.
- **BOM:** a list of library versions chosen to work together.
- **Container:** an isolated environment for running a tool such as PostgreSQL. Docker Compose starts several containers from one file.
- **Instance:** one running copy of a service.
- **Registry:** the list of service names and addresses kept by Eureka.
- **Heartbeat:** a regular message saying, "I am still running."

Start with Part A, then follow the existing build steps in order. The first useful result is seeing auth-service appear in Eureka.

---

# PART A — Understand the ideas

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

**The main point:** a monorepo puts the code in one place. It does not make the services one application. Each service still has its own jar and database and can be released separately. If changing one service forces you to release the others too, they are too closely connected. This problem is called a **distributed monolith**.

### What Maven multi-module means

A normal Maven project produces one jar. A **multi-module** project has a *parent* POM that produces nothing itself and instead lists child modules.

```
pom.xml              <- parent, packaging = pom, builds nothing
├── discovery/service-discovery/pom.xml   -> service-discovery.jar
├── gateway/api-gateway/pom.xml           -> api-gateway.jar
└── services/auth-service/pom.xml         -> auth-service.jar
```

When you run `mvn clean install` at the root, Maven finds the modules, checks which ones depend on others, and builds them in the right order. Maven calls this build process the **reactor**.

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

**BOM = Bill of Materials.** It is a POM containing a list of library versions under `dependencyManagement`. The list tells Maven which versions should work together.

Spring Cloud provides a BOM. Importing it gives your Spring Cloud libraries matching versions. Choosing versions separately can cause errors such as `NoSuchMethodError`: the code builds, but a library method is missing when the application runs.

> **How to explain it:** "`dependencyManagement` chooses library versions; it does not add the libraries to the application. Importing a BOM with `scope=import` lets us use a whole list of matching versions."

### Version compatibility — do not skip this

Spring Boot and Spring Cloud versions are **paired**. A wrong pair fails at startup with confusing errors.

| Spring Boot | Spring Cloud |
|---|---|
| 3.2.x | 2023.0.x |
| 3.3.x | 2023.0.x |
| 3.4.x | 2024.0.x |
| 3.5.x | 2025.0.x |

This project uses **Spring Boot 3.3.4 + Spring Cloud 2023.0.3 + Java 21**. Keep these versions while following the steps. If you choose to upgrade later, check the compatibility table at `spring.io/projects/spring-cloud` and choose a matching Boot and Cloud pair.

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

**1. Registration.** When a service starts, its Eureka client sends its name, IP address, port, health-check URL, `UP` status, and `instanceId` to Eureka. This adds one running copy to the service list.

**2. Heartbeat (lease renewal).** Every **30 seconds**, the client tells Eureka it is still running. If no heartbeat arrives for **90 seconds**, the registration expires and the server can remove it. The registration is also called a **lease**.

**3. Lookup (registry fetch).** Every **30 seconds**, each client downloads the service list and saves a copy in memory. **It does not call Eureka for every request.** It uses its saved list to find an address. Calls are fast, but the saved list can briefly contain old addresses.

### Client-side vs server-side load balancing

**Server-side** (classic): everything goes through one nginx / AWS ALB. The load balancer knows the instance list. One extra network hop. One more thing to keep alive.

```
booking ──▶ load balancer ──▶ event instance 2
```

**Client-side** means the calling application chooses the address. Booking-service uses its saved Eureka list, picks an event-service instance, and calls it directly. With round robin, it takes turns between the available instances.

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

Eureka may return an old service list so that callers can keep working. This favours **availability**: answering requests even when the list may not be fully current. It is an example of the AP choice in CAP. The cost is that a caller may try a service that has already stopped. Phase 7 teaches how to handle that failure.

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

A newly registered service may take up to 30 seconds to appear in responses from the server cache. We shorten these times during development so changes appear sooner. Keep the normal settings for production: frequent refreshes from many services would create extra traffic.

### Self-preservation mode

If too many expected heartbeats stop arriving, Eureka may assume there is a network problem. In **self-preservation mode**, it pauses automatic removal of registrations. The threshold described here is more than 15% missing heartbeats over 15 minutes.

The reason is simple: losing network contact does not prove a service stopped. Keeping an old address allows a caller to try again. Removing healthy services from the list could stop callers from finding them at all.

On your laptop this is annoying: you stop a service, and it stays listed as `UP` forever. So we turn it off **in dev only** and leave it on in production.

### When you should NOT use Eureka

- **You are on Kubernetes.** K8s has a built-in registry: a `Service` object gives you a stable DNS name and load balancing. Running Eureka there duplicates it.
- **You have 2–3 services with fixed addresses that never change.** Config is simpler.
- **You need strong consistency of the registry.** Eureka is AP. Consul and etcd are CP — they would rather return an error than out of date data.

We use it because it is the clearest way to *see* registration, heartbeats and eviction happening, and because plenty of real Spring shops still run it.

---

## A3. Local infrastructure with Docker Compose

We run **infrastructure in Docker** (Postgres, Redis, Kafka) and **our services from the IDE**.

Why not dockerise our services too? Because you want breakpoints, hot reload, and stack traces in your IDE. Docker for services is a deployment concern, and you said no deployment topics yet.

### One Postgres container, three databases

For local learning, one Postgres container can hold three separate databases. The important rule is that **each service uses only its own database**. In production, these databases can also run on separate servers or instances.

### Kafka in KRaft mode

Older Kafka setups used another system, **ZooKeeper**, to keep information about the Kafka cluster. **KRaft** lets Kafka manage that information itself. This project uses KRaft, so we do not need a ZooKeeper container.

### The two-listener trick (this confuses everyone)

Kafka tells clients where to reach it. That address must be correct *from the client's point of view*.

- A client **inside** the Docker network reaches it at `kafka:9092`.
- A client **on your host** (your Spring app in the IDE) reaches it at `localhost:29092`.

Kafka needs different addresses for applications inside Docker and applications on your computer. A **listener** is an address where it accepts connections. We configure both kinds. Our Spring applications run on your computer and use `localhost:29092`.

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

- `<parent>` uses `spring-boot-starter-parent` to supply library versions, plugin settings, and UTF-8 defaults.
- An empty `<relativePath/>` tells Maven to download that parent rather than search for it in `../pom.xml`.
- `<packaging>pom</packaging>` means this module produces no application jar. It holds shared build settings and the module list.
- `1.0.0-SNAPSHOT` marks a version still being developed. Maven can check for newer copies of a snapshot rather than treating it as a fixed release.

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

`<type>pom</type>` and `<scope>import</scope>` tell Maven to read this POM as a version list, rather than add it as a library. Child modules can then use those Spring Cloud versions without repeating the version numbers.

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

- The `alpine` image is a small download.
- `"5432:5432"` means `host:container`: applications on your computer use the port on the left.
- The `postgres_data` volume keeps data after a container restart. `docker compose down -v` deletes the volumes and their data for a fresh local start.
- Scripts in `/docker-entrypoint-initdb.d/` run only when the database data directory is first created. Editing the SQL later does not rerun it. A fresh local reset with `down -v` lets it run again.
- A `healthcheck` checks whether Postgres is ready to answer, rather than only whether the container has started.

### 3b. The database creation script

**File:** `infrastructure/init/01-create-databases.sql`

```sql
CREATE DATABASE auth_db;
CREATE DATABASE event_db;
CREATE DATABASE booking_db;
```

Three databases, one per service. This file is the physical shape of "database per service."

Later, Flyway will create the tables inside each service's own database. **Do not read or change another service's tables.** This keeps services separate even though the local databases share one Postgres container.

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

`--appendonly yes` tells Redis to save writes in a log on disk. A cache can often be rebuilt without this. We enable it here because Phase 8 also uses Redis for locks, where restart behaviour matters.

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

- `KAFKA_PROCESS_ROLES: broker,controller` gives this one container both jobs: storing messages and managing cluster information. A real cluster may separate them.
- The listeners have different jobs: `PLAINTEXT` accepts container connections, `CONTROLLER` handles KRaft coordination, and `PLAINTEXT_HOST` accepts connections from your computer.
- `KAFKA_ADVERTISED_LISTENERS` contains the addresses Kafka gives clients after they connect. The addresses must be reachable from those clients. Our Spring applications use `localhost:29092`.
- Replication factor `1` means each partition has one copy because there is only one broker. A common production setup uses `3` copies and `MIN_ISR` of `2`, requiring at least two in-sync copies for the intended reliable writes. Phase 10 explains these settings.
- `GROUP_INITIAL_REBALANCE_DELAY_MS: 0` removes the initial wait before assigning partitions to a consumer group. This makes local exercises start sooner.

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

1. `<parent>` points to the root POM through `relativePath`. The root lists the child in `<modules>`, and the child names the parent in `<parent>`.
2. The Eureka dependency needs no `<version>` here because the Spring Cloud BOM from Step 2d supplies it.
3. `spring-boot-maven-plugin` builds a jar you can run with `java -jar`. It includes your code, the required libraries, and a launcher. This is often called a **fat jar**.

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

`@SpringBootApplication` combines three annotations:
- `@Configuration` lets this class define objects managed by Spring, called beans.
- `@EnableAutoConfiguration` lets Spring set up features based on the libraries and settings it finds. That is why adding a dependency can change application behaviour.
- `@ComponentScan` finds classes marked with `@Component`, `@Service`, `@Repository`, and `@Controller` in this package and its subpackages. Put the main class in the top package so sibling packages are included. For example, a main class in `com.eventbooking.discovery.config` would not automatically find controllers in `com.eventbooking.discovery.api`.

`@EnableEurekaServer` turns on Eureka server setup. It adds a marker object; Spring sees that marker and creates the needed server components automatically. Other Spring annotations named `@EnableXxx` often follow the same idea.

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

- `8761` is the usual Eureka server port.
- `spring.application.name` is the service name used by Eureka, gateway routes, and Feign lookups. Without it, the service can appear as `UNKNOWN`.
- `register-with-eureka: false` stops this single server from registering with itself. Multiple Eureka servers can instead register with one another to share the service list.
- `fetch-registry: false` stops it from downloading its own service list.
- `defaultZone` is the Eureka server address clients use. This project has one zone, meaning one grouping of service locations.

### 4d. Development-only tuning

Add under `eureka:`:

```yaml
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 5000
    response-cache-update-interval-ms: 5000
```

These settings are only for development. Turning off self-preservation lets stopped services disappear from the list. Running removal and cache refresh every 5 seconds makes changes visible sooner.

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

Both exist now, so `mvn -q validate` at the root should pass without a clear error.

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

**You do not need `@EnableEurekaClient`.** Older tutorials show it. With the Spring Cloud version used here, adding `spring-cloud-starter-netflix-eureka-client` enables registration automatically, unless `eureka.client.enabled` is `false`.

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

`prefer-ip-address: true` registers the service using its IP address. Otherwise it may use your computer's hostname, such as `weloin-thinkpad`. Other computers may not know how to find that hostname.

`instance-id: ${spring.application.name}:${server.port}` identifies this running copy by its service name and port. Different copies need different ids so their registrations do not replace one another. Run auth-service on ports 8081 and 8091 later and check that Eureka lists both.

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

The response includes the port so you can tell which running copy answered. Later, with two copies and a load balancer, different response ports will show that requests are reaching both copies.

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

**This JSON is the service list.** Clients download it, keep a copy in memory, and choose an address from it. Reading it once helps make service discovery less abstract.

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

With the development settings, an instance that stops without unregistering should disappear after roughly 5–15 seconds. Normal production settings wait longer: registration expiry and the removal job both add delay. Until the list is updated, callers can still try the old address. A normal shutdown may unregister sooner, as the results note explains.

**The lesson:** a service list can contain an address that no longer works. Discovery helps find services; it does not guarantee that every call succeeds. Phase 7 adds ways to handle those failures.

### Drill 2: kill Eureka, keep the services

Stop the Eureka server. Leave auth-service running.

- auth-service logs a connection error every 30 s. Noisy, but it keeps serving traffic.
- `curl http://localhost:8081/ping` still works perfectly.
- Any service that had already fetched the registry still has its cached copy and can still route calls.

**Services can keep working when Eureka stops** if they already have a saved service list. New registrations and address changes will not reach them until Eureka returns. This is the availability choice described in A2.

Start Eureka again. auth-service re-registers on its next heartbeat.

### Drill 3: start a service before Eureka

Stop everything. Start auth-service **first**, then Eureka.

auth-service fails its first registration attempts, logs the error, and **keeps retrying**. Once Eureka appears, it registers and everything is fine. No manual restart.

Services should recover when a dependency starts later. If a service only works when everything starts in a fixed order, check whether its startup calls need retries or should happen later.

---

## Common mistakes in this phase

| Mistake | What you see | Fix |
|---|---|---|
| Forgot `spring.application.name` | Registers as `UNKNOWN` | Set the name |
| Main class in a sub-package | Controllers return 404 | Main class in the topmost package |
| `eureka-server` dependency in a service | Weird startup errors | Services use `eureka-client` |
| Edited `init.sql` after first run | New databases missing | `docker compose down -v` then up |
| Expect instant removal on kill | Out of date instance for minutes | Understand the timing table; tune in dev only |
| Used `@EnableEurekaClient` | Deprecation warning | Delete it, classpath is enough |
| Two instances overwrite each other | Only one shows in dashboard | Set `instance-id` with the port |
| Copied prod-tuned intervals into prod | Registry churn under load | Dev-only block stays dev-only |

---

## Notes for a real running system

- **Run 2–3 Eureka servers when needed.** Use `register-with-eureka: true` and list the other servers in `defaultZone`. Updates take time to spread, so their lists may briefly differ.
- **Protect Eureka endpoints.** The registry reveals service names and addresses. Require authentication or private network access.
- **Keep the normal timing defaults in production** unless measurements justify a change.
- **Supply `defaultZone` through the environment** rather than putting a deployment-specific address inside the jar.
- **Connect health checks in Phase 4.** `eureka.client.healthcheck.enabled=true` lets the service report `/actuator/health`, including database health. That helps Eureka reflect an unusable database instead of only a running process.

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
