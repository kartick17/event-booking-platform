# Learn Phases 1–5 through your existing project

Keep your code. Pause Phase 6 while you work through this guide.

This is a practice guide, not another set of files to copy. All tasks use your existing services. You make the changes yourself, one small change at a time.

## How to use each lesson

1. Read only the files named in that lesson.
2. Write what you think will happen before running anything.
3. Put a breakpoint in the named method. Send the request and watch the values and call stack.
4. Make the practice change, if the lesson has one.
5. Check the result. Explain it aloud without reading the code.

Spend 30–60 minutes per lesson. Split a lesson if needed. Speed comes from understanding one flow well, then reusing it.

Keep three short notes per lesson: **what calls this, what it does, what happens next**. Mark a lesson done only when you can explain it and repeat its check.

## Before starting

- Check `git status` so you know which changes are already yours. Save a checkpoint using your usual Git workflow.
- Start Postgres and Redis with `docker compose -f infrastructure/docker-compose.yml up -d postgres redis`.
- Run `ServiceDiscoveryApplication`, `AuthServiceApplication`, `EventServiceApplication`, then `ApiGatewayApplication` from your IDE. Booking and Kafka are not needed for this review.
- Use your existing local accounts. For admin actions, use an existing ADMIN account and a fresh login token after any role change.
- Use Postman variables for the gateway URL (`http://localhost:8080`), tokens, and event IDs. Use a future date when creating events.
- Failure exercises are temporary: restore the code or restart the stopped service before moving on. Keep your database volumes.

## 1. Understand what starts — Phase 1

**Read:** root `pom.xml`, `infrastructure/docker-compose.yml`, and each application's `application.yml`.

**Understand:** Maven builds Java applications. Docker runs your supporting tools. Each Spring application runs separately. Ports identify where they listen. A Maven module does not automatically mean a running service.

**Node connection:** a POM has some jobs similar to `package.json`; Spring's parent POM also supplies build settings and dependency versions. Docker works as it does in a Node project.

**Do:** make a small table in your notes: application, port, database, job. Open Eureka at `http://localhost:8761` and match the registered names to the YAML files.

**Check:** stop event-service. Watch its registry entry over time, then restart it. Registration and removal may take time.

**Also read:** event-service's `db/migration/V1__create_events_table.sql`. Compare it with the `events` table and `flyway_schema_history` in `event_db`. Flyway runs versioned schema changes; Hibernate's `ddl-auto: validate` checks the mapping. Do not edit a migration already applied to your database; later changes need a new migration.

**Explain:** why are `event-service`, port `8082`, and database `event_db` three different things?

## 2. Learn who creates your objects — Spring basics

**Read:** event-service's `EventServiceApplication`, `EventController`, `EventService`, and `EventRepository`.

**Understand:** a bean is an object managed by Spring. Spring finds your controller and service and passes their dependencies into their constructors. Spring Data supplies the repository implementation. Lombok's `@AllArgsConstructor` generates constructors during compilation.

**Node connection:** think of creating a service and passing a database dependency into it. Spring manages that wiring for you.

**Change:** replace `@AllArgsConstructor` with an explicit constructor in `EventService` only. Keep its existing dependency. Do not keep both constructors.

**Watch:** put a breakpoint in that constructor and restart event-service. Then send an event request. Notice that normal singleton beans are not created again for each request.

**Check:** the existing endpoint still works.

**Explain:** why is there no `new EventService(...)` in your controller?

## 3. Follow one read — Phase 4 foundation

**Read:** `EventController.getOne`, `EventService.getEventResponse`, `getById`, `EventRepository`, and `EventResponse.from`.

**Do:** request `GET /api/v1/events/{id}` through the gateway using an existing event ID. Put breakpoints in the controller, `getEventResponse`, and `getById`.

**Watch:** request ID → Java argument → loaded `Event` → `EventResponse` → JSON. If the cache skips the service method, try a new event ID or wait for expiry. Lesson 11 explains that behaviour.

**Node connection:** route handler → service → database access → response object. A Java `record` is a fixed data shape; it is not a database table by itself.

**Check:** request an ID you verified is missing. Follow `EventNotFoundException` into `GlobalExceptionHandler` and check the 404 response.

**Explain:** why does the API return `EventResponse` instead of the database entity?

## 4. Follow one write — Phase 4

**Read:** `CreateEventRequest`, `EventController.create`, `EventService.create`, the `Event` constructor, and `V1__create_events_table.sql`.

**Do:** create an event through `POST /api/v1/events` with your ADMIN token. Use the fields in your actual request record. Watch the request, `createdBy`, starting status, seats, and generated ID.

**Understand:** request checks protect the HTTP input. The `Event` object holds business rules. The SQL migration defines storage rules. These checks have different jobs.

**Practice:** send a negative price, then a valid price. Trace how the existing `@PositiveOrZero` check and `@Valid` work together. No new annotation is needed.

**Check:** invalid input returns 400 and creates no row; valid input returns 201 and creates a DRAFT event. Inspect rows with your database tool.

**Explain:** why are `id`, `status`, `availableSeats`, and `createdBy` not client-controlled request fields?

## 5. Understand saving without `save()` — Phase 4

**Read:** `EventService.publish`, `getById`, `Event.publish`, and `EventStatus.canTransitionTo`.

**Understand:** inside this transaction, JPA tracks the loaded event. Changes can be written when the transaction flushes and commits. Calling `save()` again is not required for this managed object.

**Do:** publish your DRAFT event. Watch the status change in Java, then check the database after the request finishes.

**Temporary change:** in the outer `publish` method, throw a `RuntimeException` just after `event.publish()`. Try this with another DRAFT event. The request should fail and its database status should remain DRAFT. Remove the temporary throw and repeat successfully.

**Explain:** `publish` calls `getById` in the same object. That call does not start a new transaction through `getById`'s annotation; the outer `publish` transaction already covers the work. Learn this before copying transaction annotations elsewhere.

## 6. Trace registration and passwords — Phase 2a

**Read:** `AuthController.register`, `AuthService.register`, `PasswordConfig`, `User`, and `UserRepository`.

**Do:** register a new local user. Break at email cleanup, password hashing, and saving. Compare the response fields with the stored row. Keep passwords and tokens out of logs and notes.

**Node connection:** similar to using bcrypt and a user model. Here Spring injects a `PasswordEncoder` object.

**Practice:** send the same email with different letter case. Trace the existing duplicate-email handling and inspect its response. This needs no new feature.

**Check:** one user is stored, a password hash is stored, and the response does not expose that hash.

**Explain:** why does login check a password against the hash rather than decode the stored value?

## 7. Trace login and one protected request — Phase 2a/2b

**Read:** `AuthController.login`, `AuthService.loginWithTokens`, `CustomUserDetailsService`, `JwtService`, `JwtAuthenticationFilter`, and auth `SecurityConfig`.

**Do:** login directly to auth-service on port `8081`. Break in `loadUserByUsername` and token creation. Then call `/api/v1/auth/me` on that port with the returned access token. Watch the JWT filter build `Authentication`.

**Understand:** login checks credentials using the authentication manager. A later JWT request follows a different path. `SecurityContext` holds the current request's authentication; it is not your users table.

**Check:** correct password works; wrong password fails; `/me` without a token is rejected. Observe the actual status and response at each boundary.

**Explain:** who calls `loadUserByUsername`? How does `/me` receive its `Authentication` argument?

## 8. Investigate refresh and logout — Phase 2b

**Read:** `AuthService.refresh`, `logout`, `RefreshToken`, `RefreshTokenRepository`, and `JwtProperties`.

**Do:** login, refresh once, then try the original refresh token again. Inspect the stored rows after each request. Compare the result with Phase 2b's intended rotation behaviour.

**Existing-code exercise:** the reviewed `refresh` method creates a new token but does not revoke the usable old token. Work out where old-token revocation belongs in the same successful transaction, then implement and check it. Do not rewrite the whole auth flow.

**Check:** successful refresh makes the old token unusable and the new one usable. Separately test reuse detection: revoking rows and then throwing a runtime exception in the same transaction can roll that revocation back. Ask for a focused review if you reach this case before changing transaction boundaries.

**Explain:** why can an already issued access token remain valid after logout? Revoking a refresh token does not automatically invalidate every access token.

## 9. Trace the gateway and permissions — Phase 3

**Read:** gateway `application.yml`, `AuthenticationGatewayFilter`, `PublicEndpoints`, `JwtValidator`, and event-service's `HeaderAuthenticationFilter` and `SecurityConfig`.

**Do:** trace a protected event action through port `8080`. Watch the gateway check the JWT, remove supplied identity headers, and add verified identity. Then watch event-service read those headers.

**Node connection:** gateway filters resemble proxy middleware. This gateway uses reactive types, so it is not the same execution model as your servlet-based services. Do not add blocking database calls here.

**Check:** no token is rejected; USER cannot create an event; ADMIN can. A USER token plus a fake `X-User-Roles: ROLE_ADMIN` must not gain admin permission through the gateway.

**Explain:** authentication means who you are; permission checks decide what you can do. Header trust also requires preventing untrusted callers from directly reaching internal services in deployment.

## 10. Follow one request in logs — Phase 3

**Read:** gateway and event-service `CorrelationIdFilter`, their logging patterns, gateway `RateLimiterConfig`, and the gateway CORS settings.

**Do:** send an event request with a chosen `X-Correlation-Id`. Find that value in the gateway and event logs and response headers. Check filter order in the code.

**Practice:** replace `Hello from event` in `EventController.getOne` with a useful message containing the requested event ID. Check that the existing logging pattern supplies the correlation ID.

**Also check:** explain which route has rate limiting in the current YAML. Send a browser-style OPTIONS request with an allowed Origin and requested method; inspect the CORS headers. Postman success alone does not prove a browser will allow a call.

**Explain:** correlation ID labels one request's work; it is not proof of user identity. CORS and rate limits solve different problems from authentication.

## 11. Watch the cache — Phase 5

**Read:** `CacheConfig`, `CacheProperties`, `getEventResponse`, `update`, and `listPublishedCached`.

**Understand:** a proxy is a wrapper that runs extra work around a call. With `@Cacheable`, it can return stored data without running the method body. PostgreSQL remains the source of truth.

**Do:** read an event twice. Watch the method breakpoint and cache-miss log. Temporarily shorten `cache.events.ttl`, restart, and repeat after expiry. Restore the setting afterward.

**Check:** update that event through the existing endpoint; the next read should show the change. Inspect how `@CacheEvict` removes the old entry.

**Failure exercise:** warm the event cache, stop Redis, and read again. Record the actual result and timing, then restart Redis. If the request fails, investigate whether Spring's caching advice actually uses your `CacheErrorHandler`; declaring a bean alone is not proof that the path is wired correctly.

**Explain:** why does a breakpoint inside a cached method sometimes not run? Why must a seat update check live database data?

## 12. Make two useful changes — your independent practice

Do these separately, checking each before starting the next.

### A. Make the public event list match its intended job

The reviewed `EventController.list` calls `listAll`, although the earlier phase describes a public list of published future events.

1. Create one DRAFT event and one PUBLISHED future event.
2. Check what the public list currently returns.
3. Route that controller method through the existing `listPublished` method first. Keep the response shape and pagination.
4. Check that the draft disappears and the published event remains.
5. Only then consider using `listPublishedCached`, which already returns a response wrapper. Avoid wrapping it twice.
6. If caching the list, include sorting in the cache key: the current key includes page and size but omits sort. Check two sort orders using events with different titles and start times.

### B. Keep cached seat counts fresh after seat changes

The reviewed `reserveSeats` and `releaseSeats` change database seats without the cache eviction used by other event writes.

1. Create and publish an event, then read it to warm the cache.
2. Through your existing authenticated reserve endpoint, reserve one seat. Compare the database and public response.
3. Add eviction to the outer service methods for reserve and release. Use their actual parameter name, `eventId`, in the key. Reuse the existing cache names and clear affected event lists too.
4. Repeat the read/reserve/read and read/release/read checks. Counts should match after successful writes.

Explain why this cache fix does not solve two users trying to book the last seat. That is Phase 8's separate problem.

## Ready to resume Phase 6?

You should be able to:

- Draw startup separately from request handling.
- Explain how a controller gets its service and how a repository gets its implementation.
- Follow JSON → request record → service → entity → database → response.
- Explain a transaction, a rollback, and a cache hit using observations from your project.
- Follow identity and a correlation ID through the gateway into event-service.
- Complete one small change and check success, invalid input, and permissions.

If one point is unclear, repeat that lesson. No need to restart all five phases.

When asking for a guided lesson, use: **“Teach revision lesson N using my current files. Explain one small block at a time in plain English, connect it to Node.js, then give me one task. Let me attempt it before showing the solution.”**

Start with Lesson 1 only. This guide gives the route; our chat can provide the detailed explanation when you reach each step.
