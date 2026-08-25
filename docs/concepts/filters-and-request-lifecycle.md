# Concept — Filters, Interceptors, Advice, AOP: where each one runs

You asked for this one explicitly. It is also a very common senior interview question, because
most developers use these interchangeably and cannot say which runs first.

---

## The one diagram

An HTTP request entering a Spring Boot service, top to bottom:

```
   TCP connection accepted by Tomcat
              │
              ▼
   ┌──────────────────────────────────────────┐
   │ 1. Servlet Filters (jakarta.servlet)     │  ← plain Servlet API, knows nothing about Spring MVC
   │    - CharacterEncodingFilter             │
   │    - CorrelationIdFilter (ours, Phase 3) │
   │    - DelegatingFilterProxy ──────────┐   │
   └──────────────────────────────────────│───┘
                                          │
              ┌───────────────────────────┘
              ▼
   ┌──────────────────────────────────────────┐
   │ 2. Spring Security filter chain          │  ← still Servlet Filters, just managed by Spring
   │    - SecurityContextHolderFilter         │
   │    - CorsFilter                          │
   │    - CsrfFilter                          │
   │    - OUR JwtAuthenticationFilter         │  ← Phase 2b, extends OncePerRequestFilter
   │    - UsernamePasswordAuthenticationFilter│
   │    - ExceptionTranslationFilter          │
   │    - AuthorizationFilter  ← 401/403 here │
   └──────────────────────┬───────────────────┘
                          ▼
   ┌──────────────────────────────────────────┐
   │ 3. DispatcherServlet                     │  ← Spring MVC starts here
   │    picks a HandlerMapping                │
   └──────────────────────┬───────────────────┘
                          ▼
   ┌──────────────────────────────────────────┐
   │ 4. HandlerInterceptor.preHandle()        │  ← knows WHICH controller method will run
   └──────────────────────┬───────────────────┘
                          ▼
   ┌──────────────────────────────────────────┐
   │ 5. Argument resolvers + @Valid           │
   │    JSON → DTO, validation runs here      │
   └──────────────────────┬───────────────────┘
                          ▼
   ┌──────────────────────────────────────────┐
   │ 6. AOP proxies                           │  ← @Transactional, @PreAuthorize, @Cacheable
   │    around the controller/service method  │
   └──────────────────────┬───────────────────┘
                          ▼
   ┌──────────────────────────────────────────┐
   │ 7. Your controller method                │
   └──────────────────────┬───────────────────┘
                          ▼
        exception thrown? ──▶ 8. @RestControllerAdvice
                          ▼
   ┌──────────────────────────────────────────┐
   │ 9. HandlerInterceptor.postHandle()       │
   │    then message converter DTO → JSON     │
   │    then afterCompletion()                │
   └──────────────────────┬───────────────────┘
                          ▼
             back up through every filter, in reverse
```

**And before all of this**, in our system, there is the API Gateway with its own **Gateway Filters** —
a completely separate process, on a different port, running on Netty rather than Tomcat.

---

## Each one, in plain terms

### Servlet Filter
- **API:** `jakarta.servlet.Filter`, `doFilter(request, response, chain)`
- **Sees:** raw `HttpServletRequest`. Not the controller, not the DTO, not the user.
- **Can:** block the request, wrap request/response, add headers, time the request.
- **Runs:** once per *request to the container* — but on a `forward` or `include` it can run **again**.
- **Use for:** correlation IDs, request logging, encoding, raw metrics.

### OncePerRequestFilter
- **API:** Spring's `OncePerRequestFilter`, you override `doFilterInternal(...)`.
- **It is still a Servlet Filter.** The only difference: it keeps a request attribute so that even
  with forwards, async dispatches or error dispatches, your logic runs **exactly once**.
- **Why it matters:** a JWT filter that runs twice will parse and validate the token twice. Worse,
  filters that *count* things (rate limiters, metrics) will double count.
- **Use for:** anything auth-related. This is why our JWT filter extends it in Phase 2b.

### Spring Security Filter
- Not a different technology. Spring Security is **a chain of Servlet Filters** inserted into the
  container's chain through a single `DelegatingFilterProxy` named `springSecurityFilterChain`.
- Why the proxy: filters are created by the servlet container, which knows nothing about Spring
  beans. `DelegatingFilterProxy` is a container-created filter whose only job is to look up a
  Spring bean and hand the request to it. That is the bridge between the two worlds.
- **Order matters enormously here.** Your JWT filter must run *before* `AuthorizationFilter`,
  or authorization will be decided before anybody has been authenticated → permanent 403.

### HandlerInterceptor
- **API:** `preHandle`, `postHandle`, `afterCompletion`.
- **Sees:** the `HandlerMethod` — which controller method is about to run, and its annotations.
- **Runs:** inside `DispatcherServlet`, so only for requests Spring MVC actually handles. A request
  rejected by a filter never reaches it.
- **Use for:** things that need controller knowledge — per-endpoint auditing, feature flags per
  handler, adding model attributes.
- **Do not use for:** authentication. It runs too late and misses non-MVC requests.

### @RestControllerAdvice
- Not a filter at all. A **global exception handler** for controllers.
- Catches exceptions thrown by controllers and by anything the controller called.
- **Blind spot that catches everyone:** exceptions thrown in a *filter* (including your JWT filter)
  happen **before** `DispatcherServlet`, so `@RestControllerAdvice` never sees them. That is why an
  invalid JWT produces an ugly default error page unless you handle it inside the filter or use
  Spring Security's `AuthenticationEntryPoint`. We fix exactly this in Phase 2b.

### AOP (`@Transactional`, `@PreAuthorize`, `@Cacheable`)
- Spring wraps your bean in a **proxy** — either a JDK dynamic proxy (when the bean implements an
  interface) or a CGLIB subclass (when it does not). Callers get the proxy; the proxy runs the
  advice, then calls your real method.
- **The consequence you must remember:** a proxy only works when the call comes *from outside*.
  If method `a()` in a class calls `this.b()` and `b()` is `@Transactional`, the annotation does
  **nothing** — the call never leaves the object, so it never passes the proxy. This is the
  self-invocation trap, and it is asked in interviews constantly.

### Gateway Filter (Spring Cloud Gateway)
- A different world: **Netty and reactive WebFlux**, not Tomcat and Servlets.
- Types: `pre` filters run before the request is forwarded, `post` filters run on the response.
- Global filters apply to every route; route filters to one route.
- **Never block inside a gateway filter.** There is no thread per request — a handful of event-loop
  threads serve everything. One blocking JDBC call in a filter can stall the entire gateway.

---

## Quick comparison

| | Knows the controller? | Can stop the request? | Runs on non-MVC requests? | Right for auth? |
|---|---|---|---|---|
| Servlet Filter | no | yes | yes | possible, but no Spring Security integration |
| OncePerRequestFilter | no | yes | yes | **yes** — this is the standard |
| Security filter | no | yes | yes | yes, it *is* the auth system |
| HandlerInterceptor | yes | yes | no | no — too late |
| @RestControllerAdvice | yes | no (reacts) | no | no |
| AOP | yes (method level) | yes (throws) | n/a | yes, for method-level rules |
| Gateway filter | no | yes | n/a (different process) | yes, for edge checks |

---

## How our project uses each one

| Where | What | Phase |
|---|---|---|
| Gateway filter (global) | validate JWT signature, add `X-User-Id` header | 3 |
| Gateway filter (global) | generate correlation ID | 3 |
| Servlet filter in each service | read correlation ID into the logging MDC | 3 |
| `OncePerRequestFilter` in each service | read JWT, build `Authentication`, set `SecurityContext` | 2b |
| Security config | `permitAll` / `authenticated` route rules | 2a |
| AOP `@PreAuthorize` | `hasRole('ADMIN')` on event write endpoints | 4 |
| AOP `@Transactional` | booking + outbox in one transaction | 11 |
| `@RestControllerAdvice` | one consistent JSON error shape | 2a |

---

## Memorise this

> Filters are Servlet-level and know only raw HTTP; Spring Security is a *chain of filters*
> bridged in by `DelegatingFilterProxy`; interceptors run inside `DispatcherServlet` and know the
> controller; AOP wraps the method itself and silently does nothing on self-invocation.
