# Phase 2a — Auth Service: Spring Security architecture, users, registration, login

**Time:** 5–6 hours
**Needs:** Phase 1 finished (Eureka up, Postgres up, auth-service registering).
**Pre-reading:** hashing vs encryption.

At the end of this doc you can register a user and log in. **No JWT yet** — that is Phase 2b, on
purpose. Trying to learn the Spring Security architecture and JWT at the same time is how people end
up copy-pasting a `SecurityConfig` they cannot explain.

---

# PART A — Concepts

## A1. Authentication vs Authorization

Two words that sound alike and are constantly mixed up.

**Authentication (authn) — "who are you?"** You present proof: an email and a password, a token, a
certificate. The system decides whether it believes you.

**Authorization (authz) — "what are you allowed to do?"** You are already known. Now the system
checks whether *this* user may perform *this* action.

```
POST /api/v1/events   with a valid token of a USER
  → authentication: OK, you are kartick@example.com     (200 so far)
  → authorization: creating events needs ADMIN          (403 Forbidden)
```

**The status codes carry the difference, and getting them wrong is a real bug:**

- **401 Unauthorized** — badly named. It actually means *unauthenticated*: I do not know who you are.
  Missing token, expired token, bad signature.
- **403 Forbidden** — I know exactly who you are, and the answer is still no.

A client can react to 401 by refreshing the token and retrying. Reacting that way to a 403 gives an
infinite loop. So this is not pedantry — it changes client behaviour.

## A2. Spring Security architecture

This is the section that matters most in interviews. Take it slowly.

### A2.1 It is filters all the way down

Spring Security is not "a library your controller calls." It is **a chain of Servlet Filters placed
in front of your entire application**. Your controller is never reached if the chain rejects the
request.

The bridge from the servlet container into Spring is a single filter called `DelegatingFilterProxy`,
registered under the name `springSecurityFilterChain`. The container creates it. It looks up a Spring
bean — `FilterChainProxy` — and hands the request over.

```
Tomcat
  └── DelegatingFilterProxy  (container-created, knows one bean name)
        └── FilterChainProxy  (Spring bean)
              └── matches the request to ONE SecurityFilterChain
                    └── runs that chain's filters in order
```

`FilterChainProxy` can hold several `SecurityFilterChain` objects and picks the **first one whose
matcher matches**. That is how you can have one rule set for `/api/**` and another for `/actuator/**`.

> **Interview definition:** "Spring Security is a chain of servlet filters injected through a
> `DelegatingFilterProxy`. `FilterChainProxy` picks the first matching `SecurityFilterChain` and runs
> its filters in a fixed order — the whole framework is filters plus a `ThreadLocal`."

### A2.2 The filters you must know, in order

| # | Filter | Job |
|---|---|---|
| 1 | `SecurityContextHolderFilter` | Load any existing `SecurityContext`; **always clear it at the end** |
| 2 | `CorsFilter` | Answer browser preflight `OPTIONS` requests |
| 3 | `CsrfFilter` | CSRF token check (we disable it — see A4) |
| 4 | `UsernamePasswordAuthenticationFilter` | The form-login filter. Only active for form login |
| 5 | **your `JwtAuthenticationFilter`** | *(Phase 2b)* read token → build `Authentication` |
| 6 | `ExceptionTranslationFilter` | Catch auth exceptions and turn them into 401 or 403 |
| 7 | `AuthorizationFilter` | Apply your `requestMatchers(...)` rules. Last gate before MVC |

Two rules follow directly from this table:

- Your JWT filter must be registered **before** `UsernamePasswordAuthenticationFilter`, so the
  context is populated before `AuthorizationFilter` runs.
- Anything thrown *inside* a filter is caught by `ExceptionTranslationFilter`, **not** by your
  `@RestControllerAdvice`, because the request has not reached `DispatcherServlet` yet.

### A2.3 The authentication objects

```
        Authentication (unauthenticated)
        e.g. UsernamePasswordAuthenticationToken("kartick@x.com", "rawPassword")
                     │
                     ▼
        AuthenticationManager                  ← interface, one method: authenticate()
                     │
                     ▼
        ProviderManager                        ← the standard implementation
                     │   loops over its providers, uses the first that supports() the type
                     ▼
        DaoAuthenticationProvider              ← the username/password one
                     │
          ┌──────────┴───────────┐
          ▼                      ▼
   UserDetailsService      PasswordEncoder
   loadUserByUsername()    matches(raw, hashed)
          │                      │
          └──────────┬───────────┘
                     ▼
        Authentication (authenticated)
        principal = UserDetails, credentials = null, authorities = [ROLE_USER]
                     │
                     ▼
        SecurityContextHolder.getContext().setAuthentication(auth)
```

**The pieces in plain words:**

- **`Authentication`** is one object used for two different things. Before: "here is a claim, please
  check it." After: "this claim was verified, here is who it is." The `authenticated` boolean and the
  filled-in `authorities` are what change.
- **`AuthenticationManager`** is just an interface with a single method. Everything else is detail.
- **`ProviderManager`** holds a list of `AuthenticationProvider`s. Each provider says whether it
  `supports(...)` a given token type. This is how you can support password login, LDAP and API keys in
  one app: three providers, one manager.
- **`DaoAuthenticationProvider`** is the built-in provider for username/password. It calls your
  `UserDetailsService`, then `PasswordEncoder.matches()`, then builds the authenticated token.
- **`UserDetailsService`** is a one-method interface: given a username, return a `UserDetails` or
  throw `UsernameNotFoundException`. It is **your bridge from Spring Security to your database.**
- **`PasswordEncoder`** hashes and compares. Never compare passwords with `equals()`.

### A2.4 `SecurityContextHolder` — the `ThreadLocal` that makes everything work

`SecurityContextHolder` stores the current `SecurityContext` in a **`ThreadLocal`** — a variable
whose value is private to one thread.

Why that design: Spring MVC is **thread per request**. Tomcat takes a thread from its pool, runs your
whole request on it, and returns it. So "the current user" can live in a thread-local and be readable
from *anywhere* — service, repository, even a logger — with no plumbing.

```java
var auth = SecurityContextHolder.getContext().getAuthentication();
String email = auth.getName();
```

**Two consequences you must remember:**

1. **The value must be cleared at the end.** Tomcat reuses threads. If the context is not cleared,
   the next request on that thread inherits the previous user's identity — a real, catastrophic
   security bug. `SecurityContextHolderFilter` clears it in a `finally` block. Never write your own
   filter that sets the context without a `finally` clear.
2. **It does not cross threads.** Spawn a new thread, use `@Async`, or use a `CompletableFuture`, and
   the context is empty there. Spring offers `DelegatingSecurityContextExecutor` for this. This is a
   favourite interview trap.

**Node.js bridge:** `SecurityContextHolder` is Java's answer to `AsyncLocalStorage` / `req.user`. In
Express you attach the user to the request object and pass it around. In Spring the request object is
not passed down, so the thread carries it instead.

### A2.5 Roles vs authorities, and the `ROLE_` prefix

Spring Security only knows **`GrantedAuthority`** — a string. A "role" is just an authority whose
string starts with `ROLE_`.

That prefix causes constant confusion because different methods handle it differently:

| You write | Actually checks |
|---|---|
| `hasRole("ADMIN")` | authority `ROLE_ADMIN` — prefix added for you |
| `hasAuthority("ADMIN")` | authority `ADMIN` — no prefix added |
| `hasAuthority("ROLE_ADMIN")` | authority `ROLE_ADMIN` — correct, explicit |

**Our rule for this project:** store roles in the database as `USER` and `ADMIN` (clean), add the
`ROLE_` prefix when building the authority, and use `hasRole("ADMIN")` in the code. Pick one
convention and never mix.

## A3. Password hashing

### Never store a password

If your database leaks — and databases leak — plain passwords mean every user is compromised, on
your site and on every other site where they reused it.

**Hashing is one-way.** Encryption is two-way. Passwords must be hashed, never encrypted, because
there must be no key that turns them back.

### Why not SHA-256

SHA-256 is a *fast* hash. That is exactly what makes it wrong here. A GPU computes billions of
SHA-256 hashes per second, so an attacker with your leaked table brute-forces common passwords almost
instantly.

**BCrypt is deliberately slow, and you control how slow.** The cost factor (default 10 in Spring) is
an exponent: cost 10 = 2^10 = 1024 rounds. Each +1 doubles the work. ~100 ms per hash is fine for a
login endpoint and brutal for an attacker trying billions.

### Salt

A **salt** is random data mixed into the hash so that two users with the same password get different
hashes. Without it, an attacker precomputes a table of common-password hashes once (a rainbow table)
and cracks every matching user at once.

BCrypt generates a random salt itself and stores it **inside the output string**:

```
$2a$10$N9qo8uLOickgx2ZMRZoMye  IjZAgcfl7p92ldGxad68LJZdL17lhWy
│  │  │                         │
│  │  └── 22-char salt          └── 31-char hash
│  └── cost factor 10
└── algorithm version
```

This is why you do not need a `salt` column. Everything needed to verify is in that one string.

That structure is also why `matches(raw, hashed)` works: BCrypt reads the cost and salt back out of
the stored string, hashes the raw input the same way, and compares.

### Timing attacks

Compare hashes with a constant-time comparison, not `equals()` — a comparison that exits early on the
first different byte leaks information about how much of the guess was right. `PasswordEncoder`
implementations already do this. This is one more reason never to hand-roll it.

## A4. Stateless authentication and what it costs

**Traditional (stateful):** you log in, the server creates a session in memory, and sends a
`JSESSIONID` cookie. Every later request carries the cookie; the server looks up the session.

Problems in microservices:
- Which service holds the session? All of them need it.
- Scale to 3 instances → user hits instance 2, which never saw that session. You now need sticky
  sessions or a shared session store (Redis).
- The gateway cannot make an auth decision without asking a session store.

**Stateless:** the server stores nothing. The client sends a **signed token** on every request. Any
instance of any service can verify it alone, with just the signing key.

```java
http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
```

That line tells Spring Security: never create an `HttpSession`, never read one.

**The honest trade-off:** you cannot instantly revoke a stateless token. It stays valid until it
expires, because there is nothing to delete. Fixes are short access-token lifetimes (15 minutes) plus
refresh tokens you *do* store — exactly what we build in Phase 2b.

### CSRF, and why we disable it

**CSRF (Cross-Site Request Forgery):** a malicious site makes your browser send a request to our API.
The browser **automatically attaches cookies** — so the request arrives authenticated even though the
user never meant to send it.

The attack depends entirely on credentials being sent *automatically*. A JWT in an
`Authorization: Bearer` header is **not** sent automatically — the attacker's page cannot read our
token or add that header (the browser's same-origin policy stops it).

So: **`Authorization` header + no cookies ⇒ CSRF protection is unnecessary.**

```java
http.csrf(csrf -> csrf.disable());
```

**But be careful about *why* you are writing that line.** If you later store the JWT in a cookie, CSRF
comes straight back and you must re-enable it. "Disable CSRF" is not a magic fix for a 403 — it is a
decision that depends on where the credential lives. Plenty of production incidents start with
somebody disabling CSRF to make an error go away.

## A5. Flyway — database migrations

### Why not `ddl-auto`

`spring.jpa.hibernate.ddl-auto=update` looks convenient: Hibernate reads your entities and changes
the schema. Three reasons it is wrong beyond a toy:

1. **It only adds.** Rename a column, and `update` adds a new one and leaves the old — silently.
   Drop a field, and the column stays forever.
2. **No history and no review.** Nobody can see in a PR what happened to the database.
3. **No control over the dangerous parts.** Indexes, constraints, data backfills, and the order they
   run in are all things you must decide, not guess.

### How Flyway works

You write plain SQL files with a strict name:

```
V1__create_users_table.sql
│ │  │
│ │  └── description (underscores become spaces in the log)
│ └── TWO underscores. One underscore = Flyway ignores the file
└── version number
```

On startup Flyway:
1. creates a `flyway_schema_history` table if missing,
2. reads which versions already ran,
3. runs any newer files in version order, inside a transaction where the database supports it,
4. records each one with a **checksum** of the file's contents.

**The checksum is the important part.** Change an already-applied migration file and Flyway refuses to
start:

```
Migration checksum mismatch for migration version 1
```

That is a feature. Your teammate already ran V1; editing it would leave your two databases silently
different. **The rule: applied migrations are immutable. Always add V2.** In local dev you can reset
with `docker compose down -v`.

`V` = versioned, runs once. `R__` = repeatable, re-runs whenever its checksum changes (good for views
and stored procedures).

---

# PART B — Build it

## Step 1 — Dependencies

**File:** `services/auth-service/pom.xml`, inside `<dependencies>`

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

**Why each one:**

- `starter-security` — adds the filter chain. **The moment this is on the classpath your app changes
  behaviour**: every endpoint becomes protected and a random password is printed at startup. Run the
  app now and `curl localhost:8081/ping` returns 401. That is auto-configuration being helpful and
  surprising at the same time.
- `starter-data-jpa` — Hibernate, `EntityManager`, `JpaRepository`, and the HikariCP connection pool.
- `starter-validation` — the Jakarta Bean Validation annotations (`@Email`, `@NotBlank`, `@Size`) plus
  the validator that runs them. **It is not included in `starter-web`** — forget it and `@Valid` will
  silently do nothing, which is a nasty bug to find.
- `flyway-core` + `flyway-database-postgresql` — since Flyway 10 the Postgres support is a separate
  artifact. Miss it and you get "Unsupported Database: PostgreSQL".
- `postgresql` at `runtime` scope — the JDBC driver is loaded by name at runtime, never imported in
  your code, so it does not belong on the compile classpath.

## Step 2 — Database connection

**File:** `services/auth-service/src/main/resources/application.yml`, add under `spring:`

```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/auth_db
    username: ebp
    password: ebp_password
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 30000
      pool-name: auth-pool

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        format_sql: true

  flyway:
    enabled: true
    baseline-on-migrate: true
```

**The four settings worth explaining properly:**

`ddl-auto: validate` — Hibernate does **not** touch the schema. It only compares your entities against
the real tables at startup and fails fast if they disagree. Flyway owns the schema; Hibernate checks
it. That is the correct division of labour.

`open-in-view: false` — this one is important. The default is `true`, which keeps the Hibernate
session open until the **HTTP response is written**. That sounds helpful (lazy loading works in the
controller) but it means a database connection is held for the entire request, including JSON
serialisation. Under load your connection pool empties and everything stalls. Spring Boot even logs a
warning about it. Turning it off forces you to load what you need inside the service layer, with a
transaction boundary you chose deliberately. You will hit `LazyInitializationException` at some point
— that is the setting doing its job, telling you the boundary is wrong.

`maximum-pool-size: 10` — each service instance holds up to 10 database connections. Postgres defaults
to 100 total. 3 services × 3 instances × 10 = 90. That arithmetic is a real production limit; do
it before you scale.

`baseline-on-migrate: true` — allows Flyway to start against a database that already has objects. Safe
here; think twice on a legacy database.

## Step 3 — First migration

**File:** `services/auth-service/src/main/resources/db/migration/V1__create_users_table.sql`

That path is Flyway's default. It matters.

```sql
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(60)  NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE user_roles (
    user_id BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role)
);
```

**Design decisions in these 20 lines:**

- `password_hash VARCHAR(60)` — a BCrypt string is always exactly 60 characters. Sizing the column to
  the real value documents the format. (If you ever move to Argon2, that needs a bigger column — and
  a migration, which is the point.)
- `CONSTRAINT uk_users_email UNIQUE` — the **database** enforces uniqueness. Your service will also
  check before inserting, but that check is not enough: two simultaneous registrations can both pass
  the check and both insert. Only the unique index actually prevents it. This is your first race
  condition, and the pattern — *check in code for a nice error, constrain in the database for
  correctness* — repeats through the whole project.
- `TIMESTAMPTZ` not `TIMESTAMP` — stores the time zone offset. Servers in different regions with
  naive timestamps is a bug that surfaces months later.
- A separate `user_roles` table rather than a `role` column: one user can hold several roles.
- `ON DELETE CASCADE` — delete a user, their roles go too. No orphans.
- Roles stored as `VARCHAR`, not a Postgres `ENUM` type. Adding a value to a Postgres enum needs a DDL
  migration and locks; a varchar plus a Java enum gives you type safety where you need it and freedom
  where you do not.

## Step 4 — The Role enum

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/domain/Role.java`

```java
package com.eventbooking.auth.domain;

public enum Role {
    USER,
    ADMIN;

    public String asAuthority() {
        return "ROLE_" + name();
    }
}
```

Small class, one real decision: the `ROLE_` prefix lives **here and nowhere else**. The database
stores `ADMIN`, the enum stores `ADMIN`, and the only place the Spring Security prefix is added is
this one method. When you later wonder "do I write `hasRole` or `hasAuthority`?", this method is the
answer you can point at.

## Step 5 — The User entity

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/domain/User.java`

```java
package com.eventbooking.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {
    }
}
```

**The choices that are not obvious:**

`GenerationType.IDENTITY` maps to Postgres `BIGSERIAL`. Note the cost: with IDENTITY, Hibernate
**cannot batch inserts**, because it must ask the database for each generated id immediately. For
high-volume inserts `SEQUENCE` with an allocation size is faster. For a users table, IDENTITY is
right and simpler.

`@ElementCollection` instead of `@OneToMany`: roles are **values**, not entities. `ADMIN` has no
identity of its own and no lifecycle. Modelling it as an entity would add a table, a repository and a
class for nothing.

`fetch = FetchType.EAGER` here — normally eager fetching is a mistake. Here it is deliberate: we need
the roles every single time we load a user (to build the authorities), and with `open-in-view: false`
a lazy collection would blow up with `LazyInitializationException` outside the transaction. Small,
always-needed collection ⇒ eager is correct. Be able to defend that sentence; a good interviewer will
push on it.

`protected User()` — JPA **requires** a no-argument constructor to build objects by reflection.
`protected` means JPA can use it while your own code cannot accidentally create a half-built user.

**Now the constructor and behaviour.** Add inside the class:

```java
    public User(String email, String passwordHash, String fullName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.enabled = true;
        this.roles = EnumSet.of(Role.USER);
    }

    public void grantRole(Role role) {
        this.roles.add(role);
        this.updatedAt = Instant.now();
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.updatedAt = Instant.now();
    }
```

Notice there are **no setters**. The only public constructor demands everything a valid user needs, so
an invalid `User` cannot exist. Changes happen through named methods that say what they mean. That is
the practical core of DDD: the object protects its own rules instead of trusting every caller.

`EnumSet.of(Role.USER)` in the constructor encodes the business rule *every new user is a USER*. It is
in the domain object, not scattered in a service.

**Finally the getters.** Add plain getters for `id`, `email`, `passwordHash`, `fullName`, `enabled`,
`roles` — no setters. (You may use Lombok's `@Getter` if you prefer; the docs stay explicit so nothing
is hidden.)

## Step 6 — The repository

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/infrastructure/UserRepository.java`

```java
package com.eventbooking.auth.infrastructure;

import com.eventbooking.auth.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
```

An interface with no implementation. At startup Spring Data creates a proxy class, parses each method
**name**, and generates the query: `findByEmail` becomes
`select u from User u where u.email = ?1`. `existsBy...` becomes a `select count(*)` that returns a
boolean.

`Optional<User>` rather than `User`: the return type makes "there may be no such user" impossible to
ignore. In Node you would return `null` and hope the caller checks.

`existsByEmail` instead of `findByEmail().isPresent()` — it sends `count(*)` rather than loading the
whole row. Small thing, but this endpoint runs on every registration.

## Step 7 — PasswordEncoder bean

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/config/PasswordConfig.java`

```java
package com.eventbooking.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
```

Why its own class rather than putting it in `SecurityConfig`: `SecurityConfig` will need the
`PasswordEncoder`, and `PasswordEncoder` must not need `SecurityConfig`. Keeping them apart avoids a
circular bean dependency — a mistake almost everybody makes once, and the error message is not
friendly.

Cost `12` instead of the default `10`: four times slower (2^12 vs 2^10), roughly 200–300 ms per hash on
a normal laptop. Slow enough to hurt attackers, fast enough for a login endpoint. Measure on your own
machine and pick a number; do not cargo-cult mine.

**Production note:** the modern alternative is `DelegatingPasswordEncoder`
(`PasswordEncoderFactories.createDelegatingPasswordEncoder()`). It stores the algorithm as a prefix —
`{bcrypt}$2a$12$...` — so you can migrate to a new algorithm later, upgrading each user's hash as they
log in. If this were a real product, that is what you would use from day one.

## Step 8 — UserDetailsService

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/application/CustomUserDetailsService.java`

```java
package com.eventbooking.auth.application;

import com.eventbooking.auth.domain.Role;
import com.eventbooking.auth.domain.User;
import com.eventbooking.auth.infrastructure.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        List<SimpleGrantedAuthority> authorities = user.getRoles().stream()
                .map(Role::asAuthority)
                .map(SimpleGrantedAuthority::new)
                .toList();

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .disabled(!user.isEnabled())
                .build();
    }
}
```

**This class is the entire bridge between Spring Security and your database.** Read it again with that
in mind — `DaoAuthenticationProvider` calls exactly this one method, and everything else about
password login is framework code you never touch.

**Details that matter:**

- **Constructor injection**, not `@Autowired` on a field. The dependency is `final`, so the object
  cannot exist half-built, and a plain `new CustomUserDetailsService(mockRepo)` works in a unit test
  with no Spring at all. Field injection makes both of those impossible.
- **The parameter is called `email` although the interface says "username".** Spring Security's
  "username" means "the unique thing a user types." Ours is an email. Naming the parameter honestly is
  worth more than matching the interface's vocabulary.
- **`"User not found"` — deliberately vague.** Never return "no such email" or "wrong password" as
  separate messages. That difference lets an attacker enumerate which emails are registered. One
  generic message for both cases.
- **`@Transactional(readOnly = true)`** — needed because `roles` is a collection on the entity; it
  also lets the database skip some write bookkeeping.
- Two different classes named `User` collide here, so the Spring one is written fully qualified. In a
  real codebase you would rename your entity or use a mapper. Left visible on purpose so you notice
  it.

## Step 9 — SecurityConfig

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/config/SecurityConfig.java`

The class shell and the two beans it needs:

```java
package com.eventbooking.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }
}
```

Why this bean exists: Spring Security builds an `AuthenticationManager` internally, but does not
publish it as a bean you can inject. Our login endpoint needs it. This three-line method exposes the
one Spring already built. Do **not** construct your own `ProviderManager` here — you would end up with
two managers and a very confusing bug where login works in one place and not another.

Notice we never register `DaoAuthenticationProvider` or wire the `UserDetailsService` into it. Spring
Boot does that automatically: it finds exactly one `UserDetailsService` bean and one `PasswordEncoder`
bean and assembles the provider. Auto-configuration again — and this is why having *two*
`UserDetailsService` beans breaks everything in a way that is hard to diagnose.

**Now the filter chain.** Add to the same class:

```java
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                    .requestMatchers("/actuator/health", "/ping").permitAll()
                    .anyRequest().authenticated()
            );

        return http.build();
    }
```

**Read it as a list of decisions, because that is what it is:**

- `csrf.disable()` — justified in concept A4: no cookies, credentials in a header. Not a magic fix.
- `STATELESS` — no `HttpSession` is created or read. Any instance can serve any request.
- `permitAll()` on register and login — obviously. You cannot require a token to obtain a token.
- `/actuator/health` open — Eureka calls it and has no credentials.
- **`anyRequest().authenticated()` last, and last is not decorative.** These matchers are evaluated in
  order and the **first match wins**. Put `anyRequest()` first and everything above it becomes dead
  code — your public endpoints start returning 401 and the config *looks* correct. This ordering bug
  is one of the most common in Spring Security.

**Interview note:** in Spring Security 6 this is a `SecurityFilterChain` **bean**. The old style —
extending `WebSecurityConfigurerAdapter` and overriding `configure(HttpSecurity)` — was removed in
Spring Security 6. If you see it in a tutorial, that tutorial is for version 5 and other things in it
will be wrong too.

## Step 10 — DTOs

**File:** `services/auth-service/src/main/java/com/eventbooking/auth/api/dto/RegisterRequest.java`

```java
package com.eventbooking.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email format is invalid")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 120)
        String fullName
) {
}
```

**Why a DTO and not the entity:**

1. **Different shapes.** The request has a raw `password`; the entity has a `passwordHash`. They are
   not the same object and pretending otherwise causes bugs.
2. **Security.** Bind straight to the entity and an attacker can POST `{"roles":["ADMIN"]}` and
   promote themselves. This is *mass assignment*, and it is a real vulnerability class.
3. **Your API contract stops leaking your database.** Rename a column without breaking every client.

`record` fits perfectly: immutable, no boilerplate, and Jackson deserialises it through the canonical
constructor. `record` for DTOs, `class` for entities (JPA needs mutability and a no-arg constructor).

`max = 72` on the password is not arbitrary: **BCrypt silently truncates input beyond 72 bytes.** A
user with a 100-character passphrase would have the last 28 ignored. Rejecting is honest.

**File:** `.../api/dto/UserResponse.java`

```java
package com.eventbooking.auth.api.dto;

import com.eventbooking.auth.domain.Role;
import com.eventbooking.auth.domain.User;

import java.util.Set;

public record UserResponse(
        Long id,
        String email,
        String fullName,
        Set<Role> roles
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRoles());
    }
}
```

No `passwordHash` field. That is the point of a response DTO: it is impossible to leak a field that
does not exist on the object being serialised. Returning the entity directly would eventually leak
something — if not today, then the day someone adds a `securityQuestionAnswer` column.

The static `from` keeps the mapping in one place. For two or three fields this beats adding MapStruct.

## Step 11 — Custom exceptions

**File:** `.../exception/EmailAlreadyExistsException.java`

```java
package com.eventbooking.auth.exception;

public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String email) {
        super("Email already registered: " + email);
    }
}
```

`RuntimeException`, not a checked exception — deliberately. A checked exception would force every
caller up the stack to declare or catch it, and Spring's `@Transactional` only rolls back on unchecked
exceptions by default. Unchecked is the Spring convention.

The exception carries **no HTTP status**. Domain code should not know it lives behind HTTP. The
mapping from exception to status code happens in exactly one place — next step.

## Step 12 — The registration use case

**File:** `.../application/AuthService.java`

```java
package com.eventbooking.auth.application;

import com.eventbooking.auth.api.dto.RegisterRequest;
import com.eventbooking.auth.domain.User;
import com.eventbooking.auth.exception.EmailAlreadyExistsException;
import com.eventbooking.auth.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(RegisterRequest request) {
        String email = request.email().toLowerCase().trim();

        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException(email);
        }

        String hash = passwordEncoder.encode(request.password());
        User user = new User(email, hash, request.fullName().trim());

        User saved = userRepository.save(user);
        log.info("Registered new user id={} email={}", saved.getId(), maskEmail(email));
        return saved;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
```

**Every line here is a decision:**

`toLowerCase().trim()` — email addresses are case-insensitive in practice. Without this,
`Kartick@x.com` and `kartick@x.com` become two accounts and the unique constraint never fires.
Normalise **once, at the edge of the system**, so the rest of the code can assume it.

`existsByEmail` check — gives a clean 409 instead of a raw constraint violation. It does **not**
guarantee correctness: two concurrent registrations can both pass this check. The unique index in V1 is
what actually prevents the duplicate; one request then fails with a `DataIntegrityViolationException`.
Ugly but safe. *Check for the message, constrain for the truth.* Remember this pattern — Phase 8 is
built on it.

`@Transactional` — the whole method is one database transaction. Right now there is a single write, so
it does little. It matters the moment we add "save user, save audit row, save default preferences": all
or nothing. Note that it works because Spring wraps this bean in a proxy — see the AOP section in the
[filters doc](../concepts/filters-and-request-lifecycle.md), including why calling `register()` from
another method *of this same class* would skip the transaction entirely.

`maskEmail` in the log — logs get shipped, indexed, and read by people who should not see user emails.
Log enough to debug, not enough to identify. This is the seed of the "never log secrets" habit; we
extend it to tokens in Phase 2b.

Returning the **entity** from the application layer and mapping to a DTO in the controller is a
deliberate split: the application layer speaks domain, the API layer speaks HTTP.

## Step 13 — Error response shape

**File:** `.../api/dto/ApiError.java`

```java
package com.eventbooking.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {
    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, null);
    }
}
```

**Why bother?** Because every service in this system will return errors, and the client should parse
one shape, not five. Decide it now, in the first service, and reuse the shape everywhere.

Note: reuse the **shape**, by copying this small record into each service — do **not** create a shared
`common-dto` module for it (roadmap §5.2). Six duplicated lines are cheaper than compile-time coupling
between services.

`@JsonInclude(NON_NULL)` hides `fieldErrors` when there are none, so a simple error stays simple.

## Step 14 — Global exception handler

**File:** `.../exception/GlobalExceptionHandler.java`

```java
package com.eventbooking.auth.exception;

import com.eventbooking.auth.api.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleEmailExists(EmailAlreadyExistsException ex,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }
}
```

`@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`: it applies to every controller and
writes the returned object as JSON.

**This class is the single place where a domain exception becomes an HTTP status.** The domain throws
meaning; this translates it to protocol. That separation is what lets the same service logic sit
behind a Kafka consumer later, where HTTP status codes are meaningless.

**Now validation errors.** Add to the same class:

```java
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));

        ApiError body = new ApiError(Instant.now(), 400, "Bad Request",
                "Validation failed", request.getRequestURI(), fieldErrors);

        return ResponseEntity.badRequest().body(body);
    }
```

`MethodArgumentNotValidException` is what `@Valid` throws on a `@RequestBody`. Spring's default
response for it is a wall of noise. This turns it into
`{"fieldErrors":{"email":"Email format is invalid"}}` — something a frontend can actually put next to
the right input box.

**And the catch-all.** Add:

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiError.of(500, "Internal Server Error",
                        "Something went wrong. Please try again.", request.getRequestURI()));
    }
```

**Two rules in one method:**

1. **Log the full stack trace on the server.** `log.error(msg, ex)` — the exception goes in as the
   last argument, never inside string concatenation, or you lose the stack trace.
2. **Never send the stack trace to the client.** It reveals class names, library versions, SQL and
   file paths — a map of your system for an attacker. Generic message out, full detail in the logs.

In Phase 3 we add the correlation ID to this response, so a user can quote one id and you can find the
exact stack trace.

## Step 15 — The controller

**File:** `.../api/AuthController.java`

```java
package com.eventbooking.auth.api;

import com.eventbooking.auth.api.dto.RegisterRequest;
import com.eventbooking.auth.api.dto.UserResponse;
import com.eventbooking.auth.application.AuthService;
import com.eventbooking.auth.domain.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }
}
```

**`/api/v1/` — API versioning, decided now rather than later.** The version is in the URL because it is
the only versioning scheme that is obvious in a log line, a curl command and a browser address bar.
The alternative — a custom header or content negotiation — is more "correct" by REST purists and
harder to debug. When we must break the contract, `/api/v2/events` runs beside v1 until clients move.
The rule from the roadmap holds: **adding a field is backward compatible, removing or renaming one is
not.**

**`@Valid` is what actually runs the annotations on the DTO.** Without it, `@Email` and `@NotBlank` are
inert decoration. Forgetting it is a silent failure — the code compiles, the tests you did not write
pass, and invalid data reaches your database.

**`201 Created`, not `200 OK`.** A resource was created. Status codes are part of your API contract.

The controller is four lines of real work: take input, call the application layer, map the result.
**No business logic here.** The moment a controller contains an `if` about business rules, that rule
becomes untestable without HTTP and unreachable from a Kafka consumer.

## Step 16 — Run it

```bash
cd /home/weloin/Documents/event-booking-platform
mvn -q clean install -DskipTests
```

Start Eureka, then auth-service. In the startup log you should see Flyway:

```
Migrating schema "public" to version "1 - create users table"
Successfully applied 1 migration
```

Register a user:

```bash
curl -i -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!","fullName":"Kartick"}'
```

Expect `201` and:

```json
{"id":1,"email":"kartick@example.com","fullName":"Kartick","roles":["USER"]}
```

**Now go and look at what was actually stored:**

```bash
docker exec -it ebp-postgres psql -U ebp -d auth_db \
  -c "SELECT id, email, password_hash FROM users;"
```

```
 id |        email         |                       password_hash
----+----------------------+--------------------------------------------------------------
  1 | kartick@example.com  | $2a$12$eImiTXuWVxfM37uY4JANjQ==...
```

Read the hash against the diagram in concept A3: `$2a$` algorithm, `$12$` your cost factor, then salt
and hash. **Never store a password. You can now prove that you did not.**

Look at Flyway's bookkeeping too:

```bash
docker exec -it ebp-postgres psql -U ebp -d auth_db -c "SELECT * FROM flyway_schema_history;"
```

The `checksum` column is the value that will refuse to start your app if you edit V1.

## Step 17 — Login

**File:** `.../api/dto/LoginRequest.java`

```java
package com.eventbooking.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
```

**Now the login method.** Add to `AuthService` (and inject `AuthenticationManager` through the
constructor alongside the other two dependencies):

```java
    public User login(LoginRequest request) {
        String email = request.email().toLowerCase().trim();

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        SecurityContextHolder.getContext().setAuthentication(authentication);

        log.info("Login success for {}", maskEmail(email));

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user vanished"));
    }
```

**Trace what that one `authenticate()` call sets in motion** — this is concept A2.3 actually running:

```
1. You build an UNAUTHENTICATED UsernamePasswordAuthenticationToken(email, rawPassword)
2. ProviderManager loops its providers, finds DaoAuthenticationProvider supports() this type
3. DaoAuthenticationProvider calls CustomUserDetailsService.loadUserByUsername(email)
   → your repository → SELECT * FROM users WHERE email = ?
   → not found?  UsernameNotFoundException
4. It calls passwordEncoder.matches(rawPassword, storedHash)
   → BCrypt reads cost + salt out of the stored string, hashes the input the same way, compares
   → no match?  BadCredentialsException
5. It checks the account flags: enabled, locked, expired
6. It returns a NEW, AUTHENTICATED token: principal = UserDetails,
   credentials = null (wiped on purpose), authorities = [ROLE_USER]
```

You wrote step 3's data source. The framework did the rest. **That is the whole password-login
machinery** — and now you can draw it on a whiteboard.

`credentials = null` is not an accident: the raw password is erased as soon as it has been used, so it
cannot leak through a log line or a heap dump.

Setting the `SecurityContext` here is honestly optional for this endpoint — nothing later in this
request reads it. It is here so you see explicitly *where* the context gets populated. In Phase 2b the
JWT filter does this on **every** request instead, and that is the real mechanism.

**Add the endpoint** to `AuthController`:

```java
    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = authService.login(request);
        return ResponseEntity.ok(UserResponse.from(user));
    }
```

Returning a `UserResponse` is a **temporary** shape. In Phase 2b it becomes an access token plus a
refresh token. Keeping it dumb for now means you can test the authentication chain in isolation before
adding token handling on top.

**Handle the failure case** — add to `GlobalExceptionHandler`:

```java
    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class})
    public ResponseEntity<ApiError> handleBadCredentials(Exception ex, HttpServletRequest request) {
        log.warn("Failed login attempt on {}", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(401, "Unauthorized", "Invalid email or password",
                        request.getRequestURI()));
    }
```

**Both exceptions map to the same message on purpose.** "No such user" and "wrong password" must be
indistinguishable from outside, or your login endpoint becomes a tool for checking which emails are
registered. Note also that `ex.getMessage()` is *not* included in the response — that is exactly the
detail we are hiding.

Test both paths:

```bash
# correct password → 200
curl -i -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}'

# wrong password → 401, identical message to an unknown email
curl -i -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"wrong"}'
```

---

## Break it on purpose

### Drill 1 — register the same email twice

Run the register curl twice. Second time: `409 Conflict`, clean `ApiError` JSON.

Now comment out the `existsByEmail` check and try again: you get a 500 from
`DataIntegrityViolationException` — ugly, but **the duplicate row was still prevented**. The database
constraint is the real defence; the service check only makes the error friendly. Put the check back.

### Drill 2 — the checksum rule

Edit `V1__create_users_table.sql` (add a blank line) and restart.

```
FlywayValidateException: Migration checksum mismatch for migration version 1
```

The app refuses to start. **This is correct behaviour**, and one day it will save you from a
production database that silently differs from staging. Undo the edit and it starts again.

### Drill 3 — kill the database

```bash
docker compose stop postgres
```

Now hit `/api/v1/auth/login`. The request hangs for about 30 seconds (`connection-timeout`), then
fails with a 500.

**Sit with those 30 seconds.** A caller waiting 30 s is worse than a caller failing in 1 s, because
the caller's own threads are stuck too, and *their* callers pile up behind them. That is how a
cascading failure starts, and it is the entire motivation for Phase 7. Drop `connection-timeout` to
`3000` and try again to feel the difference.

Also note: `/ping` still works. A dead database does not stop the process — which is precisely why
`eureka.client.healthcheck.enabled=true` matters. Without it, Eureka keeps reporting this instance as
`UP` and keeps sending it traffic it cannot serve.

Start Postgres again.

### Drill 4 — validation

```bash
curl -i -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"not-an-email","password":"123","fullName":""}'
```

`400` with all three field errors at once. Then delete `@Valid` from the controller and repeat: the
request now sails through to the service layer with garbage. That silent failure is why `@Valid` is
worth calling out.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| `anyRequest().authenticated()` placed first | Public endpoints return 401 | First match wins — order matters |
| Forgot `@Valid` | Invalid data reaches the database | Add it on the `@RequestBody` |
| Forgot `starter-validation` | `@Valid` silently does nothing | Add the dependency |
| Returning the entity from the controller | `passwordHash` in the JSON response | Always a response DTO |
| Different messages for unknown email vs wrong password | User enumeration | One generic message |
| Two `UserDetailsService` beans | Confusing auth failures | Exactly one |
| `PasswordEncoder` declared in `SecurityConfig` | Circular dependency at startup | Separate config class |
| Editing an applied migration | App refuses to start | Add V2; never edit V1 |
| `ddl-auto: update` with Flyway | Two things fighting over the schema | `validate` |
| `open-in-view` left `true` | Pool exhaustion under load | Set it `false` |
| Logging the raw password or the request body | Passwords in your log files | Log ids, mask emails |

---

## Production considerations

- **Rate limit login.** Without it, BCrypt cost 12 is a denial-of-service vector: an attacker sends
  thousands of login attempts and your CPU burns on hashing. Also lock an account after N failures.
- **`DelegatingPasswordEncoder`** so the algorithm can be upgraded later without a forced reset.
- **Password policy** beyond a length check: reject known-breached passwords (the Have I Been Pwned
  k-anonymity API is the standard approach).
- **Audit log** for registration, login success, login failure and role changes — a separate table,
  not just application logs.
- **Never log tokens, passwords, or full emails.**
- **Email verification** before the account becomes usable. Our `enabled` flag is already the hook for
  it.
- **Secrets from environment variables**, never from `application.yml` in Git. Phase 3 covers this
  when the JWT signing key appears — a key in a committed file is a real breach.

---

## Phase 2a checklist

- [ ] `POST /api/v1/auth/register` returns 201 and a DTO with no hash in it
- [ ] The stored `password_hash` starts with `$2a$12$`
- [ ] Registering the same email twice gives a clean 409
- [ ] Login with the right password gives 200; wrong password gives 401 with the *same* message as an
      unknown email
- [ ] Bad input gives 400 with per-field messages
- [ ] `flyway_schema_history` has one row
- [ ] You can draw the chain: `AuthenticationManager` → `ProviderManager` →
      `DaoAuthenticationProvider` → `UserDetailsService` + `PasswordEncoder`
- [ ] You can explain why `SecurityContextHolder` uses a `ThreadLocal` and what breaks if it is not
      cleared
- [ ] You can explain why CSRF is disabled here and when it would need to come back

---

## What Phase 2b does next

Login currently proves who you are and then forgets. Next: JWT.

Access tokens and refresh tokens, signing and verifying, the hand-written `OncePerRequestFilter`, where
exactly it sits in the chain, token expiry, logout with a stateless token, `@PreAuthorize`, and the
`refresh_tokens` table.

**Pre-reading for Phase 2b:** paste any JWT into jwt.io and look at the three parts. Ask yourself one
question before reading further — *if anyone can decode the payload, what stops them editing it?*
