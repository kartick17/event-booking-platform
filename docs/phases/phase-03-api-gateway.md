# Phase 3 — API Gateway: routing, edge authentication, CORS, correlation IDs

**Time:** 5–6 hours
**Needs:** Phase 2b finished (auth-service issuing tokens).
**Pre-reading:** what a reverse proxy is.

At the end of this doc, the client talks to **one port**, the JWT is checked once at the edge, every
request carries a traceable id, and a spoofed identity header is impossible.

---

# PART A — Concepts

## A1. Why a gateway at all

Right now the client calls `localhost:8081`. Soon there will be `8082` and `8083`. Without a gateway:

- **The client must know your topology.** Add a service, split one in two, change a port — every client
  changes.
- **Every service repeats the same work.** JWT verification, CORS, rate limiting, request logging, in
  all three services, drifting apart over time.
- **Every service must be reachable from the internet.** Three attack surfaces instead of one.
- **CORS becomes three configurations.**
- **No single place to trace a request.**

A gateway is a **reverse proxy that understands your system**. One address for clients, one place for
cross-cutting concerns, one door to defend.

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
| Rate limiting | Orchestrating multi-service workflows |
| Correlation IDs | Anything that blocks |

**The rule:** if it needs to know your domain, it does not belong in the gateway. A gateway that grows
business logic becomes a monolith that every team must change — the "god gateway" anti-pattern.

### Gateway vs load balancer vs BFF

- **Load balancer** (nginx, ALB): spreads traffic across identical instances. No idea what your
  services are.
- **API Gateway:** routes by path/host/header to *different* services, applies cross-cutting policy.
- **BFF (Backend For Frontend):** one gateway per client type — a mobile BFF that merges three calls
  into one small response, a web BFF that returns more. Useful at scale, unnecessary here.

## A2. Spring Cloud Gateway is reactive — this changes the rules

Auth-service runs on **Tomcat with Spring MVC**: thread per request. 200 threads, 200 concurrent
requests, each free to block on JDBC.

Spring Cloud Gateway runs on **Netty with WebFlux**: a handful of event-loop threads (typically one
per CPU core) serve **thousands** of concurrent connections. A thread never waits — it registers a
callback and moves to the next connection.

**The consequence, and it is the one thing you must not forget:**

> **Never block on a gateway thread.** No JDBC. No `RestTemplate`. No `Thread.sleep`. No `.block()`.
> One blocked event-loop thread stalls every connection it was serving — not just yours.

This is also why the gateway module must **not** include `spring-boot-starter-web`. Both starters on
the classpath and Spring Boot picks Tomcat, silently disabling the gateway. The symptom — routes
return 404 with no error — wastes hours.

**Node.js bridge:** the gateway works exactly like Node's event loop, and the same rule applies. A
blocking call in a gateway filter is the same mistake as a synchronous `fs.readFileSync` in an Express
handler under load. Auth-service is the model you *cannot* use in Node: one thread per request,
blocking freely.

## A3. How a route is defined

Every route is three things:

```
   PREDICATE            FILTERS                    URI
"does this request  "what to do to it"     "where to send it"
 match?"
Path=/api/v1/auth/**  StripPrefix, AddHeader   lb://auth-service
```

**Predicates** — `Path`, `Method`, `Header`, `Query`, `Host`, `After`/`Before` (time based), `Weight`
(for canary releases). They combine with AND.

**Filters** — two directions:
- **`pre`** filters run before forwarding: add headers, rewrite the path, check the token.
- **`post`** filters run on the way back: add response headers, record metrics.

**Route filters** apply to one route. **Global filters** apply to every route. Both run in an order you
control — and with authentication involved, order is a security property, not a preference.

**`lb://auth-service`** — the `lb` scheme means "ask the registry for instances of `auth-service` and
load-balance between them." Phase 1's Eureka client is what makes this work. Compare it to
`http://localhost:8081`, which is exactly the hardcoding we set out to remove.

## A4. Edge authentication and the trust boundary

Two models:

**Verify in every service.** Each service holds the key and checks the token itself. Zero trust, more
duplication, more places to leak the key.

**Verify at the edge.** The gateway checks the token once and forwards the identity as plain headers:

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

and the service believes it. **Instant total compromise.**

Two defences, and you need **both**:

1. **The gateway must strip these headers from every incoming request** before setting its own. Never
   trust a header a client could have sent. This is one line of config, and forgetting it is the whole
   vulnerability.
2. **Services must not be reachable from outside.** In production: a private network, security groups,
   or a service mesh with mTLS. On your laptop they are exposed — which is fine for learning, as long
   as you know it and can say so.

**Interview answer:** "We authenticate at the edge and forward identity headers. That's only safe when
the gateway strips client-supplied identity headers and the services are network-isolated. Without
both, header spoofing is trivial. If services were internet-reachable I'd verify the JWT in each
service too, using RS256 so only auth-service can mint tokens."

**Our choice:** verify at the gateway, strip aggressively, and note that services are exposed locally
for convenience.

## A5. CORS in one pass

**CORS (Cross-Origin Resource Sharing)** is a **browser** rule. A page on `localhost:3000` calling
`localhost:8080` is a *different origin* (different port is enough), so the browser blocks the response
unless the server opts in.

For anything except a simple GET, the browser first sends a **preflight**:

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

**MDC (Mapped Diagnostic Context)** is SLF4J's per-thread map of values that get added to every log
line automatically. It is a `ThreadLocal`, so — exactly like `SecurityContextHolder` — **it must be
cleared in a `finally` block**, or the next request on that pooled thread inherits the previous
request's id. Debugging with wrong correlation ids is worse than having none.

This is a hand-rolled slice of what **distributed tracing** (Micrometer Tracing, OpenTelemetry, Jaeger)
does properly, with spans and timings. Building it by hand first means the real thing will make sense
later.

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
silently. If routes ever return 404 for no reason, check this first.

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

**The same secret as auth-service.** That is HS256's cost, exactly as described in Phase 2b concept A2:
the secret now lives in two places. Both read it from `JWT_SECRET`, so in a real deployment there is
still one source — but two processes can now mint tokens, and that is the argument for RS256 the day
you add a third verifier.

**File:** `.../config/JwtProperties.java`

```java
package com.eventbooking.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, String issuer) {
}
```

**Yes, this is nearly identical to auth-service's version, and we are copying it on purpose.** Roadmap
§5.2: a shared module would couple the gateway's deployment to auth-service's. Also note the shapes
already differ — the gateway never issues tokens, so it needs no TTLs. Duplication that diverges is
duplication that was correct.

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

Pure CPU work — no I/O, nothing to block on. **This is why edge authentication is cheap:** verifying a
signature takes microseconds and needs no database, no cache, no call to auth-service. That property is
the entire argument for stateless tokens.

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

**Public paths are an allow-list, not a deny-list.** "Everything is protected unless listed" fails
safe: forget to list a new endpoint and it is *locked*, which is a bug you notice immediately. A
deny-list fails open — forget one and it is *exposed*, which nobody notices until it is too late.

**Method-specific rules.** Anyone may browse events (`GET`); only an authenticated admin may create
one. `/api/v1/events/*` matches one segment, so `/api/v1/events/42` is public but
`/api/v1/events/42/attendees` is not. `AntPathMatcher` gives `*` = one segment, `**` = any depth. That
distinction is a security boundary — read your patterns carefully.

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

`GlobalFilter` = applies to every route. `Ordered` with `-100` puts it early — **before** the routing
filter (order `10000`) that actually forwards the request. Authentication must happen before
forwarding, obviously; less obviously, order is the *only* thing enforcing that.

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

Stripping `X-User-Id` and `X-User-Roles` from the incoming request happens **before anything else**,
including the public-path check. Concept A4: a client can send any header it likes. If we only *added*
our own without removing theirs, a request arriving with `X-User-Roles: ROLE_ADMIN` would reach the
service with two values for that header — and how a downstream framework picks between them is not
something you want your security to depend on.

**Strip first, then set. Every time. No exceptions.** You will drill this in a minute.

The rest:

- **`request.mutate()`** — the reactive request is immutable, so you build a modified copy and swap it
  into the exchange. `exchange.mutate().request(...)` is the swap.
- **`"ACCESS"` type check** — Phase 2b, step 3. A refresh token must not open protected endpoints.
- **Roles as a comma-joined string** — headers are strings. Downstream services split on `,`. Simple
  and debuggable; the alternative (a JSON header) is harder to read in a proxy log for no gain.
- **`catch (JwtException)` → 401**, never 500. An invalid token is a client problem, and the status
  code says so.

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

**Reusing an incoming id** rather than always generating one is what makes tracing work across
systems. If a mobile app or an upstream gateway already started a trace, we join it instead of breaking
the chain. (In production you would validate its format so a client cannot inject `\n` into your log
lines — a real log-injection vector.)

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

- **`allowedOrigins` is an explicit list**, not `"*"`. Concept A5: `*` with `allowCredentials: true` is
  rejected outright by browsers. Beyond that, a wildcard lets any site on the internet call your API
  from a logged-in user's browser.
- **`exposedHeaders: X-Correlation-Id`** — by default JavaScript can only read a handful of response
  headers. Without this line, `response.headers.get('X-Correlation-Id')` returns `null` and your
  frontend cannot show the id in an error message.
- **`maxAge: 3600`** — the browser caches the preflight for an hour instead of sending `OPTIONS` before
  every call. A real, free latency win.
- **`allowCredentials: true`** — needed only if the browser sends cookies. With a bearer token in a
  header it is not strictly required; keep it if you might move the refresh token into an httpOnly
  cookie later.

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

## Production considerations

- **Run several gateway instances** behind a real load balancer. The gateway is stateless, so this is
  easy — and it is also why the rate limiter must live in Redis.
- **TLS terminates at the edge.** Bearer tokens on plain HTTP are readable by anyone on the path.
- **Timeouts on every route** (`spring.cloud.gateway.httpclient.connect-timeout` /
  `response-timeout`). No timeout means a slow service can hold gateway connections indefinitely.
- **Do not log the `Authorization` header.** Access logs are copied, shipped and indexed.
- **Request size limits** to blunt trivial denial-of-service.
- **Network isolation** for services, as drilled above. This is the load-bearing assumption of edge
  auth.
- **Move to RS256 + JWKS** when more than one component verifies tokens, so only auth-service can mint.
- **Real tracing** (Micrometer Tracing + OpenTelemetry) rather than hand-rolled ids, once you want
  timings and spans as well as correlation.
- **Health-based routing:** set `eureka.client.healthcheck.enabled=true` in the services so an instance
  with a dead database reports `DOWN` and stops receiving traffic.

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
