# Phase 1 — Run Results

Date: 2026-08-25
Machine: Linux 6.8.0-137-generic, host IP 192.168.1.110

## Environment note (read this first)

Java 21 and Maven were **never installed** on this machine. `/usr/lib/jvm` holds
only Java 17, and `mvn` is not on the PATH.

Everything below was built and run with IntelliJ's bundled tools instead:

- Maven: `/opt/idea-IU-262.8665.337/plugins/maven-plugin/lib/maven3/bin/mvn`
- JDK: `/opt/idea-IU-262.8665.337/jbr/bin/java` (JBR 25.0.3)

JDK 25 built code for Java 21 and ran the Java 21 jars, so the checks below did run successfully. However, this setup uses tools bundled with the IDE. Their paths may change after an IDE update. Install a separate Java 21 JDK and Maven before Phase 2.

---

## Step 1 — Folder skeleton

Created:

```
services/auth-service
services/event-service
services/booking-service
gateway/api-gateway
discovery/service-discovery
infrastructure/init
```

**Result: PASS.** Note that `event-service`, `booking-service` and
`api-gateway` are still empty, so git will not track them until they hold a
file.

---

## Step 2 — Root POM

Wrote `pom.xml` at the repo root: Spring Boot parent 3.3.4, Java 21,
Spring Cloud 2023.0.3 BOM, two modules listed.

First checkpoint, before the module POMs existed:

```
$ mvn -q validate
[ERROR] Child module .../discovery/service-discovery/pom.xml does not exist
[ERROR] Child module .../services/auth-service/pom.xml does not exist
```

**Result: PASS.** This error was expected. It shows that Maven read the `<modules>` list and tried to find the modules.

After both module POMs were written:

```
$ mvn -q validate
(silent, exit 0)
```

**Result: PASS.**

---

## Step 3 — Infrastructure

Wrote `infrastructure/docker-compose.yml` (postgres 16, redis 7, kafka 3.7 in
KRaft mode) and `infrastructure/init/01-create-databases.sql`.

```
$ docker compose config -q
(valid)

$ docker compose up -d
$ docker compose ps
ebp-kafka      apache/kafka:3.7.0   Up   0.0.0.0:29092->29092/tcp
ebp-postgres   postgres:16-alpine   Up (healthy)   0.0.0.0:5432->5432/tcp
ebp-redis      redis:7-alpine       Up (healthy)   0.0.0.0:6379->6379/tcp
```

Kafka shows no health status because the compose file defines no healthcheck
for it. That is the doc's design, not a fault.

Verification:

```
$ docker exec ebp-postgres psql -U ebp -d postgres -c "\l"
 auth_db    | ebp | UTF8 | ...
 booking_db | ebp | UTF8 | ...
 event_db   | ebp | UTF8 | ...

$ docker exec ebp-redis redis-cli ping
PONG

$ docker exec ebp-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
(empty, exit 0)
```

**Result: PASS.** All three databases created by the init script. Empty Kafka
topic list with exit 0 means the broker is up and answering.

Trap to remember: the init SQL runs only once, when the data volume is empty.
Editing it later does nothing until `docker compose down -v`.

---

## Step 4 — Eureka server

Wrote the module POM, `ServiceDiscoveryApplication.java`, and
`application.yml` with the dev-only tuning block.

Build:

```
$ mvn -q clean install -DskipTests
(exit 0)

discovery/service-discovery/target/service-discovery-1.0.0-SNAPSHOT.jar   55M
services/auth-service/target/auth-service-1.0.0-SNAPSHOT.jar              47M
```

Run:

```
Tomcat started on port 8761 (http) with context path '/'
Started ServiceDiscoveryApplication in 2.94 seconds

$ curl -o /dev/null -w '%{http_code}' http://localhost:8761/
200
```

**Result: PASS.** Dashboard answers on 8761, instance table empty at this point.

---

## Step 5 — auth-service registers

Wrote the module POM, `AuthServiceApplication.java`, `PingController.java`,
and `application.yml` (port 8081, `prefer-ip-address`, `instance-id` with port).

Registration, from the auth-service log:

```
DiscoveryClient_AUTH-SERVICE/auth-service:8081: registering service...
DiscoveryClient_AUTH-SERVICE/auth-service:8081 - registration status: 204
```

`204 No Content` is Eureka saying "accepted."

Dashboard row:

```
AUTH-SERVICE    n/a (1)    auth-service:8081
```

Endpoint:

```
$ curl http://localhost:8081/ping
{"service":"auth-service","port":"8081","status":"alive"}
```

Raw registry, the thing worth reading once:

```
$ curl -H "Accept: application/json" http://localhost:8761/eureka/apps

app: AUTH-SERVICE
  auth-service:8081  192.168.1.110:8081  UP
  leaseInfo: renewalIntervalInSecs 30, durationInSecs 90
  healthCheckUrl: http://192.168.1.110:8081/actuator/health
```

**Result: PASS.** Eureka lists the IP address rather than the computer name because `prefer-ip-address: true` is set. The 30/90 values are the heartbeat interval and registration expiry time from the guide.

---

## Step 6 — Two instances, one name

```
$ java -jar services/auth-service/target/auth-service-1.0.0-SNAPSHOT.jar --server.port=8091

$ curl http://localhost:8091/ping
{"status":"alive","service":"auth-service","port":"8091"}
```

Registry now:

```
app: AUTH-SERVICE
  auth-service:8091  192.168.1.110:8091  UP
  auth-service:8081  192.168.1.110:8081  UP
```

**Result: PASS.** One application name, two instances. The `instance-id`
setting keeps them apart; with the default id they could have collided.

---

## Step 7 — Break it on purpose

### Drill 1 — kill an instance

Stopped the 8091 copy. The registry dropped to one instance **immediately**,
not after the 5–15 s the doc predicts.

This was a normal shutdown, so Spring Boot told Eureka directly to remove the service using DELETE. Eureka did not need to wait for missing heartbeats. The longer times in the guide apply when a service stops without sending that message, such as after `kill -9`, a crash, or a lost network connection.

**Result: PASS**, with that correction to expectations.

### Drill 2 — kill Eureka, keep the service

Stopped the Eureka server.

```
$ curl -o /dev/null -w '%{http_code}' http://localhost:8761/
000                        <- Eureka is down

$ curl http://localhost:8081/ping
{"service":"auth-service","port":"8081","status":"alive"}
```

auth-service logged the failure every 30 s:

```
Request execution failed with message: I/O error on GET request for
"http://localhost:8761/eureka/apps/delta": Connection refused

DiscoveryClient_AUTH-SERVICE/auth-service:8081 - was unable to refresh its
cache! This periodic background refresh will be retried in 30 seconds.
```

Still serving normally 35 s later.

**Result: PASS.** The running service still answered requests while Eureka was stopped. Eureka could not share new addresses, but stopping it did not stop this service.

### Drill 3 — service running before Eureka

auth-service was already up with no registry to talk to. Eureka was then
started fresh.

```
18:25:02  Started ServiceDiscoveryApplication        (Eureka back up)
18:25:18  DiscoveryClient_AUTH-SERVICE/auth-service:8081 - Re-registering apps/AUTH-SERVICE
18:25:18  DiscoveryClient_AUTH-SERVICE/auth-service:8081: registering service...
18:25:18  registration status: 204
```

**Result: PASS.** Re-registered by itself 16 seconds after Eureka returned, with
no restart. Startup order does not matter.

The client sends a heartbeat every 30 seconds. After Eureka restarted with an empty list, it answered a heartbeat with 404 because the instance was unknown. The client then registered again. In this run that took 16 seconds.

---

## Checklist

- [x] `mvn clean install` at the root builds both modules
- [x] `docker compose ps` shows postgres, redis, kafka all running
- [x] `auth_db`, `event_db`, `booking_db` all exist
- [x] Eureka dashboard opens at :8761
- [x] auth-service appears as `UP` in the dashboard
- [x] `curl localhost:8081/ping` returns JSON
- [x] Read the raw `/eureka/apps` JSON
- [x] Two instances registered under one application name
- [x] Killed Eureka and saw the services keep working
- [ ] Can explain, out loud: registration, heartbeat, lease expiry, registry
      fetch, client-side load balancing

**9 of 10.** Commands can check the running system. The final item asks you to explain what you learned in your own words.

---

## Open items before Phase 2

1. Install real Java 21 and Maven. Everything above ran on IntelliJ's bundled
   JDK 25 and bundled Maven.
2. `gateway/api-gateway`, `services/event-service` and `services/booking-service`
   are still empty folders, so git will not track them.
3. Client-side load balancing was never actually watched. Two instances were
   registered, but nothing called through `lb://auth-service` to see the port
   flip between responses. That needs the gateway, which is Phase 3.
