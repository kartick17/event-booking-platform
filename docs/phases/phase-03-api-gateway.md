# Phase 3 — API Gateway: routing, edge authentication, CORS, correlation IDs

**Time:** 5–6 hours
**Needs:** Phase 2b finished (auth-service issuing tokens).
**Pre-reading:** what a reverse proxy is.

By the end, clients use **one port**. The gateway checks JWTs, forwards requests, replaces client-supplied identity headers, and gives each request an id you can follow in the logs. Services must also be protected from direct outside access, as A4 explains.

---

## In plain English

The gateway becomes the single address clients call. It checks the login token and sends each request to the right service. The services still do their own work, such as creating events or bookings.

For example, the client sends a booking request to port 8080. The gateway checks the token, finds booking-service through Eureka, and forwards the request. The client does not need to know booking-service's address.

Words used in this phase:

- **Gateway / reverse proxy:** an application that receives requests and forwards them to other applications.
- **Route:** a rule deciding where a request goes.
- **Predicate:** a condition a request must match, such as its URL path.
- **Header:** extra information sent with an HTTP request or response.
- **Identity header:** a header saying which user the gateway verified.
- **Reactive / event loop:** handling many connections with a small number of workers, without making those workers wait for slow work.
- **CORS:** browser rules about calling a server at a different origin. An origin includes the protocol, host, and port.
- **Correlation ID:** one label carried across services so you can find all logs for the same request.

Start by understanding a route. Then follow one authenticated request through the gateway, including the headers it removes and adds.

---

# PART A — Understand the ideas

## A1. Why a gateway at all

Right now the client calls `localhost:8081`. Soon there will be `8082` and `8083`. Without a gateway:

- **Clients need every service address.** Changing a port or adding a service can require client changes.
- **Services repeat common work**, such as checking tokens, setting browser access rules, and logging requests. Their settings can become different over time.
- **Every public service needs its own protection.**
- **Browser access rules need configuring in several places.**
- **There is no shared place to start following a request.**

A gateway receives requests at one address and forwards them to the right service. It also provides one place for common checks, such as validating tokens. This type of forwarding application is called a **reverse proxy**.

```
              WITHOUT                              WITH
   client ──▶ :8081 auth                client ──▶ :8080 gateway ──▶ auth
   client ──▶ :8082 event                                     ├──▶ event
   client ──▶ :8083 booking                                   └──▶ booking
   (3 public doors, 3× CORS,            (1 public door, 1× CORS,
    3× JWT code)                         1× JWT code)
```

### What belongs in the gateway, and what does not

| Belongs | Does not belong |
|---|---|
| Routing | Business logic |
| Authentication (is this token valid?) | Fine-grained authorization ("is this *your* booking?") |
| Coarse authorization (`/admin/**` needs ADMIN) | Database access |
| CORS | Data transformation between services |
| Rate limiting | Coordinating multi-service workflows |
| Correlation IDs | Anything that blocks |

**Keep business decisions in the services.** The gateway can check whether a token is valid, but booking-service decides whether a booking belongs to the current user. Putting all business rules in the gateway would make every service depend on changes to it.

### Gateway vs load balancer vs BFF

- **Load balancer** (nginx, ALB): chooses between running copies of the same application.
- **API Gateway:** chooses between different services using the request path, host, or headers, and applies shared checks.
- **BFF (Backend For Frontend):** a backend designed for one client type. A mobile app may need a smaller combined response than a web app. We do not need separate BFFs here.

## A2. Spring Cloud Gateway is reactive — this changes the rules

Auth-service uses **Tomcat and Spring MVC**. A worker thread handles a request and can wait for the database. While it waits, that worker cannot handle another request.

The gateway uses **Netty and WebFlux**. A small set of event-loop threads handles many connections. When an operation needs time, the worker arranges to continue later and moves on to other connections. It must not sit waiting.

**The consequence, and it is the one thing you must not forget:**

> **Never block on a gateway thread.** No JDBC. No `RestTemplate`. No `Thread.sleep`. No `.block()`.
> One blocked event-loop thread stalls every connection it was serving — not just yours.

This is also why the gateway module must **not** include `spring-boot-starter-web`. Both starters on
the classpath and Spring Boot picks Tomcat, without a clear error disabling the gateway. The symptom — routes
return 404 with no error — wastes hours.

**If you know Node.js:** the gateway follows a similar event-loop idea. A blocking operation in a gateway filter can delay many connections, just as a synchronous file read can delay other requests in Express. The Spring MVC services use a different model, where a worker may wait for one request.

## A3. How a route is defined

Every route is three things:

```
   PREDICATE            FILTERS                    URI
"does this request  "what to do to it"     "where to send it"
 match?"
Path=/api/v1/auth/**  StripPrefix, AddHeader   lb://auth-service
```

**Predicates are matching conditions.** Examples are `Path`, `Method`, `Header`, `Query`, and `Host`. `After`/`Before` match times; `Weight` splits traffic. Conditions on a route are combined with AND: they must all match.

**Filters** — two directions:
- **`pre`** filters run before forwarding: add headers, rewrite the path, check the token.
- **`post`** filters run on the way back: add response headers, record metrics.

**Route filters** run for one route. **Global filters** run for every route. Their order matters: for example, remove untrusted user headers before adding the verified user's headers.

**`lb://auth-service`** means: find running copies named `auth-service` using the saved Eureka list, then choose one. The address is no longer fixed to `http://localhost:8081`.

## A4. Edge authentication and the trust boundary

Two models:

**Check the token in every service.** Each service validates the token itself. This repeats some work and requires distributing verification keys safely.

**Check the token at the gateway.** The gateway verifies the token, then sends the verified user details to the service as headers:

```
client ──[Authorization: Bearer eyJ...]──▶ gateway
                                             │ verify signature + expiry
                                             │ strip Authorization (optional)
                                             │ add X-User-Id, X-User-Roles
                                             ▼
                                     ┌──[X-User-Id: kartick@x.com]──▶ event-service
```

Services then read a header instead of parsing a token. Simple, fast, no key needed downstream.

### The security hole this creates — read this twice

If a client can reach a service **directly**, it can send whatever it likes:

```bash
curl http://localhost:8082/api/v1/events -H "X-User-Roles: ROLE_ADMIN"
```

If the service accepts those headers from a direct caller, that caller can pretend to be another user or an admin.

Two defences, and you need **both**:

1. **Remove client-supplied identity headers at the gateway.** Add new ones only after checking the token. Otherwise the client could choose its own user or role.
2. **Block direct outside access to the services.** In production, use private networking or equivalent controls. A service mesh can also use mTLS, where services prove their identity to each other. Local direct access is convenient for learning, but it is not this production protection.

> **How to explain it:** "The gateway checks the token and forwards user headers. This is safe only if it removes client-supplied copies and outside callers cannot reach services directly. If services were public, they would need to verify tokens themselves too. RS256 would let them verify without having the key that creates tokens."

**Our choice:** verify at the gateway, strip aggressively, and note that services are exposed locally
for convenience.

## A5. CORS in one pass

**CORS (Cross-Origin Resource Sharing)** is enforced by browsers. A page at `localhost:3000` and an API at `localhost:8080` have different origins because their ports differ. The API must allow that origin before the browser lets the page read the response.

Some browser requests first send an **OPTIONS preflight**. This asks the server whether the intended method and headers are allowed:

```
OPTIONS /api/v1/events
Origin: http://localhost:3000
Access-Control-Request-Method: POST
Access-Control-Request-Headers: authorization, content-type
```

The server must answer with matching `Access-Control-Allow-*` headers before the real request is sent.

**Three things people get wrong:**

- **curl and Postman ignore CORS entirely.** It is a browser rule. "It works in Postman but not in the
  browser" is almost always CORS.
- **The preflight carries no `Authorization` header**, so it must be permitted without authentication.
  Block `OPTIONS` in your security rules and every browser call fails.
- **`allowedOrigins("*")` with `allowCredentials(true)` is rejected by browsers** — that combination is
  forbidden by the spec. List real origins.

Configure it **at the gateway only**. If both gateway and service add the headers you get duplicates,
and browsers reject `Access-Control-Allow-Origin` appearing twice.

## A6. Correlation IDs

One user action now touches gateway → booking → event → Kafka → event again. When it fails, you have
five sets of logs and no way to line them up.

A **correlation ID** is one random id generated at the edge and carried through everything:

```
gateway generates  X-Correlation-Id: 7f3a91c2
   → forwards it on every downstream call
      → each service puts it in the logging MDC
         → every log line includes it
            → Kafka messages carry it as a header
```

Then:

```bash
grep 7f3a91c2 *.log
```

and you have the whole story in order.

**MDC (Mapped Diagnostic Context)** stores values that the logger adds to each log line. In a normal servlet request, these values belong to the current thread. Clear them in a `finally` block: the thread will be reused, and the next request must not keep the previous request's id.

This is a small introduction to **distributed tracing**: following one operation across several services. Tools such as Micrometer Tracing, OpenTelemetry, and Jaeger also record individual steps, called spans, and their timings.

---

# PART B — Build it

## Step 1 — The module

**File:** `gateway/api-gateway/pom.xml`

Same parent block as the other modules (`relativePath` is `../../pom.xml`), then:

```xml
    <artifactId>api-gateway</artifactId>
    <name>api-gateway</name>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
```

**Note what is missing: `spring-boot-starter-web`.** Concept A2 — adding it breaks the gateway
without a clear error. If routes ever return 404 for no reason, check this first.

`data-redis-**reactive**`, not the blocking one, for the same reason. The rate limiter runs on the
event loop.

Register the module in the root `pom.xml`:

```xml
        <module>gateway/api-gateway</module>
```

## Step 2 — Main class

**File:** `gateway/api-gateway/src/main/java/com/eventbooking/gateway/ApiGatewayApplication.java`

```java
package com.eventbooking.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

Startup should log `Netty started on port 8080`. If it says **Tomcat**, `starter-web` is on your
classpath.

## Step 3 — First route

**File:** `gateway/api-gateway/src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway

  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://auth-service
          predicates:
            - Path=/api/v1/auth/**

eureka:
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
  instance:
    prefer-ip-address: true
```

**Read the route as a sentence:** *any request whose path starts with `/api/v1/auth/` goes to whichever
instance of `auth-service` the registry offers.*

`lb://auth-service` matches the `spring.application.name` you set in Phase 1 — that is the only link
between them. Get the name wrong and you get a 503 with `Unable to find instance`.

No `StripPrefix` filter, because our services already serve `/api/v1/auth/...`. Keeping gateway paths
and service paths identical means a log line reads the same everywhere. Rewriting is a feature to reach
for only when you must.

**Test it now** (Eureka, auth-service, gateway all running):

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}' | jq
```

Same response as port 8081 — through the gateway. **You now have one door.**

### The discovery locator, and why we do not use it

Spring Cloud Gateway can auto-create a route for every registered service:

```yaml
      discovery:
        locator:
          enabled: true
```

Then `/AUTH-SERVICE/**` routes automatically. Convenient, and wrong for us:

- Every registered service becomes publicly reachable the moment it starts — including internal ones
  you never meant to expose.
- Paths carry the service name, so renaming a service breaks every client.
- You cannot attach per-route filters cleanly.

**Explicit routes are a security boundary.** If it is not in the list, it is not reachable. Keep it
that way.

## Step 4 — Gateway JWT properties

**File:** `application.yml`, add:

```yaml
jwt:
  secret: ${JWT_SECRET:ZmFrZS1kZXYtc2VjcmV0LWNoYW5nZS1tZS0zMi1ieXRlcy1taW5pbXVtLWxlbmd0aA==}
  issuer: auth-service
```

**The gateway needs the same HS256 secret as auth-service.** Both read `JWT_SECRET`, but both processes can now create as well as verify signatures. RS256 can separate those abilities if more verifiers are added.

**File:** `.../config/JwtProperties.java`

```java
package com.eventbooking.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, String issuer) {
}
```

**Copy this small class into the gateway.** A shared module would make services depend on the same code release. The gateway version is already different: it only verifies tokens, so it does not need token-lifetime settings.

## Step 5 — Token verification at the edge

**File:** `.../security/JwtValidator.java`

```java
package com.eventbooking.gateway.security;

import com.eventbooking.gateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Component
public class JwtValidator {

    private final SecretKey key;
    private final String issuer;

    public JwtValidator(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.issuer();
    }

    public Claims validate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

Token signature verification uses local calculations. It does not need a database lookup or a request to auth-service. That helps the gateway check requests without waiting for another service.

## Step 6 — Which paths are public

**File:** `.../security/PublicEndpoints.java`

```java
package com.eventbooking.gateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.AntPathMatcher;

import java.util.List;

public final class PublicEndpoints {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health"
    );

    private static final List<String> PUBLIC_GET_PATHS = List.of(
            "/api/v1/events",
            "/api/v1/events/*"
    );

    private PublicEndpoints() {
    }

    public static boolean isPublic(ServerHttpRequest request) {
        String path = request.getURI().getPath();

        if (request.getMethod() == HttpMethod.OPTIONS) {
            return true;
        }
        if (PUBLIC_PATHS.stream().anyMatch(p -> MATCHER.match(p, path))) {
            return true;
        }
        return request.getMethod() == HttpMethod.GET
                && PUBLIC_GET_PATHS.stream().anyMatch(p -> MATCHER.match(p, path));
    }
}
```

**Three decisions here:**

**`OPTIONS` is always public** — concept A5. Preflight requests carry no `Authorization` header. Miss
this and every browser call fails while curl works fine.

**List public paths; protect everything else.** If you forget to add a new public endpoint, it remains protected and the error is easy to notice. Listing only protected paths risks leaving a forgotten endpoint public.

**Check the HTTP method as well as the path.** Public event browsing uses `GET`; creating an event needs an admin. `/api/v1/events/*` matches one path segment, such as `/api/v1/events/42`, but not `/api/v1/events/42/attendees`. With `AntPathMatcher`, `*` matches one segment and `**` can match several.

**Note that `/api/v1/auth/logout` is deliberately absent**, so it requires a valid token. It is listed
here for you to notice: every path in or out of this file is a decision.

## Step 7 — The authentication filter

**File:** `.../security/AuthenticationGatewayFilter.java`

The class shell:

```java
package com.eventbooking.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class AuthenticationGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationGatewayFilter.class);

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    private final JwtValidator jwtValidator;

    public AuthenticationGatewayFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
```

`GlobalFilter` runs for every route. `Ordered` with `-100` runs this filter before the routing filter at `10000`. This makes authentication happen before the request is forwarded.

**Now the filter body.** Add:

```java
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Never trust identity headers sent by the client.
        ServerHttpRequest sanitised = request.mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USER_ROLES_HEADER);
                })
                .build();

        if (PublicEndpoints.isPublic(sanitised)) {
            return chain.filter(exchange.mutate().request(sanitised).build());
        }

        String token = extractToken(sanitised);
        if (token == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }

        try {
            Claims claims = jwtValidator.validate(token);

            if (!"ACCESS".equals(claims.get("type", String.class))) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "Wrong token type");
            }

            ServerHttpRequest enriched = sanitised.mutate()
                    .header(USER_ID_HEADER, claims.getSubject())
                    .header(USER_ROLES_HEADER, String.join(",", roles(claims)))
                    .build();

            return chain.filter(exchange.mutate().request(enriched).build());

        } catch (JwtException ex) {
            log.debug("Rejected token for {}: {}", request.getURI().getPath(), ex.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }
```

**The most important seven lines are the first ones.**

**Remove `X-User-Id` and `X-User-Roles` before any other identity decision, including the public-path check.** If you only add a new header, a client-supplied value may remain alongside it. Do not let the receiving service choose between a fake role and a verified role.

**Strip first, then set. Every time. No exceptions.** You will drill this in a minute.

The rest:

- **`request.mutate()`** creates a changed copy of the reactive request because the original cannot be edited directly. `exchange.mutate().request(...)` installs that copy.
- **Check the `"ACCESS"` type** so refresh tokens are not accepted for ordinary protected endpoints.
- **Join roles with commas** because headers carry strings. The receiving service splits on `,`.
- **Return 401 for `JwtException`.** A rejected token is a login problem, not an internal 500 error.

**Add the two helpers:**

```java
    private String extractToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return (header != null && header.startsWith("Bearer "))
                ? header.substring(7)
                : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> roles(Claims claims) {
        Object raw = claims.get("roles");
        return raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
```

**And the rejection**, which has to write a response by hand because we are not in Spring MVC:

```java
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");

        String body = """
                {"status":%d,"error":"%s","message":"%s"}
                """.formatted(status.value(), status.getReasonPhrase(), message);

        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
```

Notice what did **not** happen: `chain.filter(...)` was never called. The request stops here and never
reaches any service. That is what "rejecting at the edge" means — a bad token costs you one signature
check and nothing downstream.

The Java text block keeps the JSON readable. For a fixed shape like this, hand-writing it beats
serialising an object on the event loop.

## Step 8 — Correlation IDs

**File:** `.../filter/CorrelationIdFilter.java`

```java
package com.eventbooking.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID = "X-Correlation-Id";

    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String id = correlationId;

        exchange.getResponse().getHeaders().add(CORRELATION_ID, id);

        return chain.filter(
                exchange.mutate()
                        .request(r -> r.header(CORRELATION_ID, id))
                        .build());
    }
}
```

**Order `-200`: before authentication.** Deliberate — a *rejected* request still needs an id, or your
401s are the ones you cannot trace, and those are exactly the ones users complain about.

**Reuse an existing correlation id when joining a request from another system.** This keeps its earlier logs connected to ours. In production, validate the id format so a caller cannot insert line breaks such as `
` into logs.

**Echoing it in the response header** means a user can copy the id from their browser's network tab
into a support ticket, and you can find their exact request.

**File:** `.../filter/LoggingFilter.java`

```java
package com.eventbooking.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public int getOrder() {
        return -150;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String id = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID);
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();

        return chain.filter(exchange).doFinally(signal -> {
            long took = System.currentTimeMillis() - start;
            var status = exchange.getResponse().getStatusCode();
            log.info("[{}] {} {} -> {} ({} ms)", id, method, path, status, took);
        });
    }
}
```

**`doFinally` is the reactive equivalent of a `finally` block.** The filter returns immediately — it
does not wait for the response — so the logging must be attached as a callback that fires when the
`Mono` completes, whether it succeeded, failed, or was cancelled.

**Do not use MDC here.** In reactive code the work moves between threads, so a `ThreadLocal` set at the
start is gone by the time the response completes. Putting the id directly in the message is the correct
approach on the gateway. MDC *does* work in the blocking services — that is the next step, and the
difference between the two is worth noticing.

## Step 9 — Reading the correlation id in the services

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/config/CorrelationIdFilter.java`

Auth-service is Tomcat + Spring MVC, so here a plain servlet filter and MDC are exactly right:

```java
package com.eventbooking.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

**`MDC.clear()` in a `finally` is not optional.** Tomcat reuses threads. Without it, request #2 on that
thread logs request #1's correlation id, and you will chase a ghost through your logs for an afternoon.
Same reasoning as `SecurityContextHolder` in Phase 2a — thread-locals on pooled threads must always be
cleaned up.

**Then make it visible.** In `services/auth-service/src/main/resources/application.yml`:

```yaml
logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] [%X{correlationId}] %-5level %logger{36} - %msg%n"
```

`%X{correlationId}` pulls the value out of the MDC. **Every log line in this service now carries the
id, with no change to any logging statement.** That is what makes MDC worth the thread-local risk.

Copy this filter and this pattern into every service you build from here on.

## Step 10 — CORS

**File:** `gateway/api-gateway/src/main/resources/application.yml`, under `spring.cloud.gateway`:

```yaml
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOrigins:
              - "http://localhost:3000"
              - "http://localhost:5173"
            allowedMethods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]
            allowedHeaders: "*"
            exposedHeaders:
              - X-Correlation-Id
            allowCredentials: true
            maxAge: 3600
```

**Each setting, and the reason:**

- **List `allowedOrigins`** rather than using `"*"`. Browsers reject a wildcard origin with `allowCredentials: true`. List only the browser applications that should read these responses.
- **`exposedHeaders: X-Correlation-Id`** lets browser JavaScript read that response header. Otherwise `response.headers.get('X-Correlation-Id')` may return `null`.
- **`maxAge: 3600`** lets the browser reuse the preflight permission for an hour, reducing repeated `OPTIONS` requests.
- **`allowCredentials: true`** allows browser credentials such as cookies. A bearer token explicitly added in a header does not itself require this option; a later refresh-cookie design may.

**Configure CORS in exactly one place.** Gateway *and* service both adding headers produces duplicates,
and the browser rejects a doubled `Access-Control-Allow-Origin`.

## Step 11 — Rate limiting

**File:** `.../config/RateLimiterConfig.java`

```java
package com.eventbooking.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null) {
                return Mono.just("user:" + userId);
            }
            String ip = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(addr -> addr.getAddress().getHostAddress())
                    .orElse("unknown");
            return Mono.just("ip:" + ip);
        };
    }
}
```

**The `KeyResolver` answers "who is this bucket for?"** Per authenticated user when we know them,
falling back to IP when we do not.

Why not IP always: everyone behind one office NAT or one mobile carrier shares an IP, and you would
throttle a whole building because of one user. Why not user always: unauthenticated endpoints —
`/login`, `/register` — have no user, and those are exactly the ones that need protection from brute
force.

**Note the ordering dependency:** this reads `X-User-Id`, which the authentication filter sets. So the
rate-limit filter must run **after** it (`-100`), which it does — route filters run after global
filters of negative order.

**Apply it to the login route** in `application.yml`:

```yaml
        - id: auth-service
          uri: lb://auth-service
          predicates:
            - Path=/api/v1/auth/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 5
                redis-rate-limiter.burstCapacity: 10
                redis-rate-limiter.requestedTokens: 1
                key-resolver: "#{@userOrIpKeyResolver}"
```

**And point it at Redis:**

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

**How the token bucket works** — this is the algorithm, and it is worth knowing by name:

- The bucket holds up to `burstCapacity` (10) tokens.
- It refills at `replenishRate` (5) tokens per second.
- Each request takes `requestedTokens` (1). Empty bucket → **429 Too Many Requests**.

So: a burst of 10 immediately, then a steady 5 per second. Bursts are allowed, sustained abuse is not
— which matches how real clients behave far better than a flat "5 per second" cap.

**Redis, not memory**, because there will be several gateway instances and the limit must be shared. A
per-instance counter with 3 instances is really a 3× limit. Spring Cloud Gateway ships a Lua script
that makes the check-and-decrement atomic inside Redis — the same distributed-atomicity problem you
will meet again in Phase 8.

**Failure mode to know:** if Redis is down, the default behaviour is to **deny** requests. Safe, but it
turns a cache outage into a full outage. Decide deliberately: fail-closed (safe) or fail-open
(available). Phase 7 is about exactly this kind of choice.

## Step 12 — Add the remaining routes

Prepare the routes for services you have not built yet:

```yaml
        - id: event-service
          uri: lb://event-service
          predicates:
            - Path=/api/v1/events/**

        - id: booking-service
          uri: lb://booking-service
          predicates:
            - Path=/api/v1/bookings/**
```

Calling them now returns **503 Service Unavailable** — the route matches, but Eureka has no instance to
send it to. Recognise that error now, so that in Phase 4 you know it means "service not registered",
not "route wrong". A **404** would mean the route itself did not match.

## Step 13 — Run the whole thing

Order: Eureka → auth-service → gateway.

```bash
# 1. login through the gateway
ACCESS=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}' | jq -r .accessToken)

# 2. protected endpoint through the gateway
curl -s http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer $ACCESS" | jq

# 3. no token — rejected AT THE GATEWAY, auth-service never sees it
curl -i http://localhost:8080/api/v1/auth/me

# 4. correlation id echoed back
curl -si http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer $ACCESS" \
  | grep -i correlation
```

**Watch both log windows during step 3.** The gateway logs a 401. **Auth-service logs nothing at all.**
The request never reached it. That silence is the gateway doing its job.

### The full request lifecycle, end to end

You now have every piece needed to trace one request:

```
1.  Client:   POST /api/v1/auth/me, Authorization: Bearer eyJ...
2.  Netty accepts the connection on an event-loop thread
3.  CorrelationIdFilter        (-200) generate/reuse X-Correlation-Id
4.  LoggingFilter              (-150) start the timer
5.  AuthenticationGatewayFilter(-100) STRIP client X-User-* headers
                                      → public path? no
                                      → extract bearer token
                                      → verify signature, exp, iss, type
                                      → add X-User-Id, X-User-Roles
6.  RequestRateLimiter                token bucket in Redis for this user
7.  RoutePredicateHandlerMapping      match Path=/api/v1/auth/**
8.  LoadBalancerClientFilter          lb://auth-service → ask Eureka's local cache
                                      → pick an instance, round robin
9.  NettyRoutingFilter                forward to http://192.168.1.5:8081/api/v1/auth/me
--- network hop, different process, different threading model ---
10. Tomcat takes a thread from its pool
11. CorrelationIdFilter (servlet)     read the header into the MDC
12. Spring Security filter chain
13. JwtAuthenticationFilter           verify the token again, build Authentication
14. AuthorizationFilter               endpoint requires authentication → allowed
15. DispatcherServlet → AuthController.me(Authentication)
16. Argument resolver reads SecurityContextHolder (ThreadLocal, this thread)
17. Response → Jackson → JSON
18. back through the servlet filters, MDC.clear() in the finally
--- back across the network ---
19. LoggingFilter's doFinally: "[7f3a91c2] GET /api/v1/auth/me -> 200 OK (14 ms)"
20. Response to the client, with X-Correlation-Id
```

**Read that list again slowly.** Every numbered line is something you wrote or configured yourself. If
you can talk through it without notes, you can talk through any Spring microservice request.

**Notice step 13:** auth-service verifies the token *again*, even though the gateway already did. That
is deliberate belt-and-braces for the one service that owns identity. Event-service and booking-service
will instead trust the `X-User-Id` header — the trust boundary from concept A4.

---

## Break it on purpose

### Drill 1 — spoof the identity header

```bash
curl -i http://localhost:8080/api/v1/auth/me -H "X-User-Id: attacker@evil.com"
```

`401`. The gateway stripped the header before doing anything else, and with no bearer token there was
nothing to authenticate.

**Now the important half.** Bypass the gateway and hit the service directly:

```bash
curl -i http://localhost:8081/api/v1/auth/me -H "X-User-Id: attacker@evil.com"
```

`401` too — but only because auth-service verifies the JWT itself (step 13). **Event-service, which
will trust the header, would have been compromised.**

That is concept A4 made concrete: **edge authentication is only as strong as the network isolation
behind it.** On your laptop every port is open. In production these services sit on a private network
with only the gateway exposed. Say that sentence in an interview and you are ahead of most candidates.

### Drill 2 — comment out the stripping

Temporarily remove the `headers.remove(...)` lines and restart:

```bash
curl -i http://localhost:8080/api/v1/events -H "X-User-Roles: ROLE_ADMIN"
```

Now a public-path request reaches the service carrying an attacker-supplied `ROLE_ADMIN`. Once
event-service exists and reads that header, this is a full privilege escalation **through** the
gateway.

**Put the lines back.** Then remember the rule: strip first, then set, always.

### Drill 3 — kill auth-service, keep the gateway

```
503 Service Unavailable
```

Two things to notice. First, the gateway stayed up — a dead service does not take the platform with it.
Second, look at **how long** the 503 took: the gateway waited for a connection timeout.

Now imagine 500 requests per second hitting that route. Every one holds a connection for the timeout
duration. **That is a cascading failure forming**, and it is precisely what Phase 7's circuit breaker
prevents: after N failures, fail *instantly* instead of waiting.

### Drill 4 — kill Eureka, keep everything else

Requests **still work**. The gateway is serving from its cached registry (Phase 1, concept A2).

Now restart auth-service on a *different* port while Eureka is down. The gateway keeps routing to the
old address and fails, because nothing can tell it the truth. **Discovery being down does not break
what is running; it breaks the ability to change.**

### Drill 5 — trip the rate limiter

```bash
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/v1/auth/login \
    -X POST -H "Content-Type: application/json" \
    -d '{"email":"x@x.com","password":"wrong"}'
done; echo
```

Roughly ten `401`s, then a run of `429`s. Wait two seconds and a few more get through — the bucket
refilled at 5 per second.

Then inspect the state:

```bash
docker exec -it ebp-redis redis-cli KEYS 'request_rate_limiter*'
```

The tokens and timestamps live in Redis, shared across every gateway instance.

### Drill 6 — trace one request end to end

Make a request, take the `X-Correlation-Id` from the response, and grep both log outputs for it. One id,
two processes, one story. Multiply that by five services and you understand why this filter exists.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| `starter-web` on the gateway classpath | Tomcat starts, routes 404 | Remove it |
| Blocking call in a filter | Whole gateway stalls under load | Reactive only, never `.block()` |
| Identity headers not stripped | Client can forge identity | Strip before setting |
| `OPTIONS` not public | Browser works in Postman, fails in the app | Allow preflight |
| CORS on gateway *and* service | Duplicate header, browser rejects | One place only |
| Discovery locator enabled | Internal services publicly reachable | Explicit routes |
| Auth filter ordered after routing | Token never checked | Negative order |
| `lb://` name ≠ `spring.application.name` | 503 `Unable to find instance` | Match the names |
| Correlation id not echoed to the client | Users cannot report a traceable id | `exposedHeaders` |
| MDC not cleared | Wrong ids in logs | `finally { MDC.clear(); }` |
| MDC used in gateway filters | Empty values | Reactive code changes threads |
| Rate limit key = IP only | A whole office throttled together | Key by user, fall back to IP |
| Secret differs between gateway and auth | Every token rejected | One `JWT_SECRET` source |

---

## Notes for a real running system

- **Run multiple gateway instances behind a load balancer** if one process is not enough. Use shared Redis rate-limit data so each instance follows the same limits.
- **Use TLS/HTTPS at the public entry point.** Plain HTTP exposes bearer tokens to someone on the network path.
- **Set route timeouts** using `spring.cloud.gateway.httpclient.connect-timeout` and `response-timeout`, so slow calls do not wait forever.
- **Never log the `Authorization` header.** Log copies can otherwise expose working tokens.
- **Limit request sizes** so oversized requests cannot consume unlimited resources.
- **Prevent direct outside access to services.** Header-based identity depends on this protection.
- **Consider RS256 and JWKS** when several components verify tokens, keeping the signing key only in auth-service.
- **Add tracing tools** such as Micrometer Tracing and OpenTelemetry when you need timings for individual steps as well as request ids.
- **Report dependency health** with `eureka.client.healthcheck.enabled=true`, so Eureka learns when a service cannot use its database.

---

## Phase 3 checklist

- [ ] Everything works through `:8080`
- [ ] A request with no token is rejected at the gateway — auth-service logs nothing
- [ ] A spoofed `X-User-Id` does not survive the gateway
- [ ] You bypassed the gateway and understand why that is only safe with network isolation
- [ ] `X-Correlation-Id` comes back in the response and appears in service logs
- [ ] The rate limiter returns 429 and its state is visible in Redis
- [ ] Killing auth-service gives 503, and you noticed how long it took
- [ ] Killing Eureka does not break existing routing
- [ ] You can talk through all 20 steps of the request lifecycle
- [ ] You can explain why MDC works in the services but not in the gateway

---

## What Phase 4 does next

The platform has a door and a doorman. Now it needs something to sell.

**Event Service:** the first service that is purely business. CRUD, capacity and available seats,
status transitions, DTO/validation/exception patterns carried over from auth, `@PreAuthorize` for
admin-only writes, and the `X-User-Id` header consumed for the first time. Plus the transaction
boundaries and aggregate design that Phase 8's concurrency work depends on.

**Pre-reading for Phase 4:** bounded context, and why entities must never leak out of a controller.
