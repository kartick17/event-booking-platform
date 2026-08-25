# Phase 2b — Auth Service: JWT, refresh tokens, RBAC

**Time:** 4–5 hours
**Needs:** Phase 2a finished (register + login working).
**Pre-reading:** paste any JWT into jwt.io and look at the three parts.

At the end of this doc, login returns tokens, protected endpoints demand them, roles are enforced, and
you can explain every step out loud.

---

# PART A — Concepts

## A1. What a JWT actually is

**JWT = JSON Web Token.** Three Base64url-encoded parts joined by dots:

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJrYXJ0aWNrQHgu... . 4pB7XlKQ9m2f...
      HEADER                    PAYLOAD                  SIGNATURE
```

**Header** — which algorithm signed this:
```json
{ "alg": "HS256", "typ": "JWT" }
```

**Payload** — the claims:
```json
{
  "sub": "kartick@example.com",
  "roles": ["ROLE_USER"],
  "iat": 1735000000,
  "exp": 1735000900,
  "jti": "8f2c1e...",
  "type": "ACCESS"
}
```

**Signature** — `HMAC_SHA256(base64url(header) + "." + base64url(payload), secretKey)`.

### The single most important fact

**Base64 is encoding, not encryption.** Anyone holding the token can read the payload. jwt.io does it
in your browser with no key.

So:
- **Never put secrets in a JWT.** No passwords, no card numbers, no personal data you would not print
  on a postcard.
- What stops someone editing `"roles":["ROLE_ADMIN"]` into the payload? **The signature.** Change one
  byte of the payload and the signature no longer matches, because they cannot recompute it without
  the secret key. Verification fails and the token is rejected.

> **Interview definition:** "A JWT is a signed, not encrypted, JSON document. The signature proves it
> was issued by us and has not been modified — it does not hide anything. Anyone can read the payload;
> nobody can change it without the key."

### Registered claims worth knowing

| Claim | Meaning | Do we use it |
|---|---|---|
| `sub` | subject — who the token is about | yes, the email |
| `iat` | issued at | yes |
| `exp` | expires at | yes, non-negotiable |
| `jti` | JWT id, unique per token | yes, needed for revocation |
| `iss` | issuer | yes, `auth-service` |
| `aud` | audience — who it is for | later, if services need different tokens |
| `nbf` | not before | no |

All times are **seconds since epoch, UTC**. Never local time.

## A2. HS256 vs RS256 — a real architecture decision

**HS256 (HMAC + SHA-256)** — **one shared secret** signs and verifies.

**RS256 (RSA)** — a **key pair**: the private key signs, the public key verifies.

Why this matters here: in our system, the **gateway** verifies tokens, and later each **service** may
verify them too. With HS256 every one of those needs the shared secret — and a secret that four
services hold is a secret that can leak from four places. Worse, any of them could *mint* tokens,
because signing and verifying use the same key.

With RS256, only auth-service holds the private key. Everyone else gets the public key, which can be
published openly. A leaked public key allows verification, never forgery.

| | HS256 | RS256 |
|---|---|---|
| Keys | one shared secret | private + public |
| Who can create tokens | anyone with the secret | only the private key holder |
| Key distribution | secret to every verifier | public key anywhere |
| Speed | faster | slower to sign |
| Right for | single service, or one trusted verifier | multiple services, third parties |

**Our choice: HS256, with the gateway as the only verifier.** Reasons: it keeps the moving parts down
while you learn, and our design has services trust the gateway's `X-User-Id` header rather than
re-verifying (Phase 3 explains why that is safe *only* when services are unreachable from outside).

**Be honest about it in an interview:** "We used HS256 with a single verification point at the gateway.
For a larger estate I'd move to RS256 with a JWKS endpoint, so services fetch the public key and no
service but the issuer can mint tokens." That answer shows you know the trade, not just the code.

## A3. Why two tokens

A single long-lived token is a bad deal either way: short expiry means users log in constantly, long
expiry means a stolen token is usable for weeks.

Two tokens split the problem:

| | Access token | Refresh token |
|---|---|---|
| Lifetime | 15 minutes | 7 days |
| Sent | on every request | only to `/auth/refresh` |
| Stored server-side | **no** | **yes**, in a table |
| Can be revoked | not really | yes, delete the row |
| Contains | sub, roles, exp | a random opaque id (or a minimal JWT) |

The flow:

```
login ──▶ access(15m) + refresh(7d)
   │
   ├─ normal requests use the access token
   │
   └─ access token expires → 401
          │
          └─▶ POST /auth/refresh with the refresh token
                 → new access token (+ a new refresh token: rotation)
```

**Why this is a genuine security win:** the access token travels on every request — more chances to
leak through logs, proxies, browser extensions — but it is worthless 15 minutes later. The refresh
token is long-lived but travels rarely, and because we store it, we can delete it. That is how you get
logout in a "stateless" system.

## A4. The revocation problem

**Stateless means the server holds nothing.** So how do you log someone out, or block a stolen token,
before it expires?

Honest answer: **you cannot delete a JWT.** It is a signed piece of paper already in the wild. You can
only limit the damage. The options:

| Approach | How | Cost |
|---|---|---|
| Short expiry | 15 min, accept the window | free, but a 15-minute hole |
| Refresh token table | delete the row on logout | one DB write; access token still lives up to 15 min |
| Blacklist by `jti` | store revoked ids in Redis until `exp` | a Redis lookup per request — you just gave up statelessness |
| Token version | a counter on the user; tokens carry it; bump it to kill all tokens | a user lookup per request |

**Our choice: short expiry + a refresh token table.** Logout deletes the refresh row, so no new access
tokens can be minted; the current access token stays valid for at most 15 minutes.

**Say this out loud once so it sticks:** logout in a stateless system is *best effort*. If you truly
need instant revocation, you need per-request server state, and then you are not stateless any more.
That is the trade, and pretending otherwise is how people build systems they cannot explain.

### Refresh token rotation and reuse detection

Every refresh returns a **new** refresh token and invalidates the old one. That is **rotation**.

The reason is clever and worth understanding. Suppose an attacker steals a refresh token:

```
attacker refreshes → gets token B, our row now holds B
victim refreshes with the old token A → A is not valid any more
```

That failure is a **signal**. A valid-looking but already-used refresh token means one of two people
holds a copy. The correct response is not just to reject it — it is to **delete every refresh token
for that user**, forcing a fresh login and killing the attacker's session too. This is *reuse
detection*, and it is what turns rotation from bookkeeping into an actual defence.

## A5. Where the filter sits

Our `JwtAuthenticationFilter` extends `OncePerRequestFilter` (see the
[filters doc](../concepts/filters-and-request-lifecycle.md) for why "once" matters) and is registered
**before** `UsernamePasswordAuthenticationFilter`:

```
SecurityContextHolderFilter
CorsFilter
  ▼
JwtAuthenticationFilter        ← ours: read header, verify, build Authentication, set context
  ▼
UsernamePasswordAuthenticationFilter
ExceptionTranslationFilter
AuthorizationFilter            ← reads the context we just populated. 401 / 403 decided here
  ▼
DispatcherServlet → controller
```

**Two rules follow, and both are things people get wrong:**

1. **A missing or invalid token must not throw from the filter.** The filter simply leaves the context
   empty and calls `chain.doFilter(...)`. `AuthorizationFilter` then decides: if the endpoint is
   public, fine; if not, 401. Throwing inside the filter bypasses your `@RestControllerAdvice`
   entirely — the request never reaches `DispatcherServlet` — and you get an ugly default error page.
2. **Never `return` without calling `chain.doFilter(...)`** unless you deliberately want to end the
   request there. Forgetting it makes the request vanish silently with an empty 200.

---

# PART B — Build it

## Step 1 — The JWT library

**File:** `services/auth-service/pom.xml`

```xml
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
```

Three artifacts, on purpose: you compile against `jjwt-api` only, and the implementation is swapped in
at runtime. Your code cannot accidentally depend on internals. Same reason the Postgres driver was
`runtime` scope in Phase 2a.

Explicit `<version>` here because JJWT is not in the Spring Boot BOM — Spring does not manage it, so
you must. **Better practice:** move the version to a `<jjwt.version>` property in the root POM, so all
three stay in step and every service uses the same one.

## Step 2 — Configuration as a typed object

**File:** `services/auth-service/src/main/resources/application.yml`

```yaml
jwt:
  secret: ${JWT_SECRET:ZmFrZS1kZXYtc2VjcmV0LWNoYW5nZS1tZS0zMi1ieXRlcy1taW5pbXVtLWxlbmd0aA==}
  issuer: auth-service
  access-token-ttl: PT15M
  refresh-token-ttl: P7D
```

**`${JWT_SECRET:default}` is Spring's syntax for "environment variable, or this fallback".** The
fallback exists so a fresh clone runs; the environment variable is what you set in any real
environment.

**Say the rule plainly: a signing key committed to Git is a breach.** Anyone with repo access — now or
in five years, including anyone who forks it — can mint admin tokens. Our default is deliberately a
throwaway dev value. Real secrets live in environment variables, and in production in a secret manager
(Vault, AWS Secrets Manager, Kubernetes Secrets).

`PT15M` and `P7D` are **ISO-8601 durations**. Spring converts them straight to `java.time.Duration`.
`PT15M` = 15 minutes, `P7D` = 7 days. Much harder to misread than `900000`.

**File:** `.../config/JwtProperties.java`

```java
package com.eventbooking.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotBlank String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
}
```

Enable it on the main class:

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {
```

**Why not just scatter `@Value("${jwt.secret}")` around?**

- **One typed object.** `Duration`, not a `long` you must remember is milliseconds.
- **`@Validated` fails at startup** if the secret is missing — not at 3am on the first login. Fail
  fast, at boot, is a production principle worth internalising.
- **Testable.** `new JwtProperties("secret", "issuer", ofMinutes(15), ofDays(7))` — no Spring context
  needed.
- **Discoverable.** One class lists every JWT knob. `@Value` strings hide in ten files.

## Step 3 — JwtService: the key and token creation

**File:** `.../application/JwtService.java`

Start with the class and the signing key:

```java
package com.eventbooking.auth.application;

import com.eventbooking.auth.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(
                properties.secret().getBytes(StandardCharsets.UTF_8));
    }
}
```

The key is built **once in the constructor**, not on every call. Key derivation is not free and this
method runs on every single request that carries a token.

`Keys.hmacShaKeyFor` **throws if the secret is shorter than 256 bits (32 bytes)** for HS256. That is
the library refusing to let you weaken your own security — a short key makes brute-forcing the
signature realistic. If it throws, generate a proper one:

```bash
openssl rand -base64 48
```

**Now the access token.** Add:

```java
    public String generateAccessToken(String email, Collection<String> authorities) {
        Instant now = Instant.now();

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(email)
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .claim("roles", authorities)
                .claim("type", "ACCESS")
                .signWith(signingKey)
                .compact();
    }
```

**Each claim, and why it is there:**

- `.id(...)` → `jti`. A unique id per token. You need it the day you add a revocation list, and you
  cannot add it retroactively to tokens already issued.
- `.subject(email)` → `sub`. Who this is. The filter reads it back to build the `Authentication`.
- `.issuer(...)` → `iss`. Lets a verifier reject tokens minted by some other system that happens to
  share an algorithm.
- `.expiration(...)` → `exp`. **The single most important claim.** A token without `exp` is a password
  that never changes.
- `"roles"` — a **custom** claim. This is what makes the token self-contained: the gateway can decide
  `hasRole('ADMIN')` without one call to auth-service. That is the whole point of stateless auth.
- `"type": "ACCESS"` — so an access token can never be replayed at the refresh endpoint, or the other
  way round. Without it, a stolen 15-minute access token could be used to mint fresh tokens forever.
  Small claim, real attack closed.
- `.compact()` builds and signs, returning the final string.

**Note what is *not* in there:** no name, no phone number, no anything private. Concept A1 — the
payload is readable by anyone holding the token.

## Step 4 — JwtService: verification

Add to `JwtService`:

```java
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .clockSkewSeconds(60)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
```

**What this one call actually checks:**

1. The token has three parts and decodes.
2. **The signature matches** — recomputed with our key. This is where a tampered payload dies.
3. `exp` is in the future.
4. `nbf`, if present, is in the past.
5. `iss` equals ours (because we asked with `requireIssuer`).

Anything wrong throws a subclass of `JwtException`: `ExpiredJwtException`, `SignatureException`,
`MalformedJwtException`.

`clockSkewSeconds(60)` matters more than it looks. Servers' clocks drift. Without tolerance, a token
issued by a machine 2 seconds ahead is "not valid yet" on another machine, and you get random,
unreproducible auth failures. 60 seconds is the usual allowance.

Note `parseSignedClaims`, not `parseClaimsJwt`. The "signed" variant refuses unsigned tokens. This
closes the classic **`alg: none` attack**: an attacker rewrites the header to say "no algorithm",
strips the signature, and a naive parser accepts it. Old JWT libraries fell for this; modern ones must
be asked for the signed variant.

**Now the small readers.** Add:

```java
    public String extractEmail(String token) {
        return parseAndValidate(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        Object roles = parseAndValidate(token).get("roles");
        return roles instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
    }
```

The unchecked cast is unavoidable: JSON arrays deserialise to `List<Object>`, and generics are erased
at runtime so nothing can verify the element type. The `instanceof` pattern plus `String::valueOf`
keeps a malformed token from becoming a `ClassCastException` deep in the filter.

## Step 5 — The refresh token table

**File:** `.../resources/db/migration/V2__create_refresh_tokens_table.sql`

**V2, not an edit to V1.** Concept A5 in Phase 2a — applied migrations are immutable.

```sql
CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    token       VARCHAR(255) NOT NULL,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_refresh_token UNIQUE (token)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
```

**The design decisions:**

- `UNIQUE (token)` — also gives you the index that every lookup uses. Every refresh request is
  `WHERE token = ?`; without an index that is a full table scan on a table that grows forever.
- `idx_..._user_id` — for "revoke everything for this user" on logout and on reuse detection.
- `idx_..._expires_at` — for the cleanup job that deletes expired rows. Without it, that job gets
  slower every day until it becomes an incident.
- `revoked BOOLEAN` rather than deleting the row: for a while you can still tell the difference between
  "never existed" (someone is guessing) and "used and rotated" (possible theft). That distinction is
  what makes reuse detection possible.

**This table is the price of being able to log out.** Worth naming plainly: the "stateless" system has
exactly one piece of state, and this is it.

## Step 6 — Entity and repository

**File:** `.../domain/RefreshToken.java`

```java
package com.eventbooking.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RefreshToken() {
    }

    public RefreshToken(String token, Long userId, Instant expiresAt) {
        this.token = token;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable() {
        return !revoked && expiresAt.isAfter(Instant.now());
    }

    public void revoke() {
        this.revoked = true;
    }
}
```

(Add getters for `token`, `userId`, `expiresAt`, `revoked`.)

`Long userId` instead of `@ManyToOne User user`. **This is a deliberate modelling choice, not
laziness.** A `@ManyToOne` drags a whole `User` object into every refresh operation and invites lazy
loading problems. Refresh only ever needs the id. Storing the id keeps the two aggregates separate —
which is exactly the discipline you need later when `booking` references an `event` that lives in
another service and cannot be a foreign key at all. Practising it here makes Phase 6 feel natural.

`isUsable()` on the entity, not in the service: the rule "usable means not revoked and not expired"
belongs to the thing it describes. Every caller gets it right for free.

**File:** `.../infrastructure/RefreshTokenRepository.java`

```java
package com.eventbooking.auth.infrastructure;

import com.eventbooking.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.userId = :userId and r.revoked = false")
    int revokeAllForUser(Long userId);
}
```

`@Modifying` tells Spring Data this query changes data, so it uses `executeUpdate()` instead of
expecting a result set. Forget it and you get a confusing "Not supported for DML operations" error.

Why a bulk `update` rather than loading the rows and calling `revoke()` on each: one SQL statement
instead of N+1. A user with 40 devices would otherwise mean 41 queries.

**The catch, and it is a real one:** a `@Modifying` query goes **straight to the database and bypasses
the persistence context**. Entities already loaded in this transaction keep their stale `revoked`
value. Add `@Modifying(clearAutomatically = true)` if you read those entities again in the same
transaction. This is a classic production bug.

## Step 7 — Issuing tokens on login

**File:** `.../api/dto/AuthResponse.java`

```java
package com.eventbooking.auth.api.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
    public static AuthResponse of(String accessToken, String refreshToken, long expiresInSeconds) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds);
    }
}
```

`tokenType: "Bearer"` and `expiresIn` in **seconds** follow the OAuth2 response convention (RFC 6749).
Following a standard shape costs nothing and means client libraries already know what to do with it.

`expiresIn` lets a client refresh *before* expiry instead of waiting for a 401. Fewer failed requests,
smoother UX.

**Now wire it into `AuthService`.** Add the new dependencies to the constructor (`JwtService`,
`RefreshTokenRepository`, `JwtProperties`) and add:

```java
    @Transactional
    public AuthResponse loginWithTokens(LoginRequest request) {
        String email = request.email().toLowerCase().trim();

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user vanished"));

        String accessToken = jwtService.generateAccessToken(email, authorities);
        String refreshToken = createRefreshToken(user.getId());

        log.info("Issued tokens for {}", maskEmail(email));

        return AuthResponse.of(accessToken, refreshToken,
                jwtProperties.accessTokenTtl().toSeconds());
    }

    private String createRefreshToken(Long userId) {
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(jwtProperties.refreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(token, userId, expiresAt));
        return token;
    }
```

**Two things to notice:**

The authorities come from the **`Authentication` object**, not from a second database read. The
`DaoAuthenticationProvider` already loaded them through your `UserDetailsService` during
`authenticate()`. Reading them again would be a wasted query — and, worse, a chance for the two values
to disagree.

**The refresh token is a random UUID, not a JWT.** That is deliberate. A refresh token is only ever
checked against our database, so it needs no self-contained claims. An opaque random string leaks
nothing if it appears in a log, and it cannot be inspected by whoever holds it.

**Production note:** we store the refresh token in plain text. A leaked database dump therefore hands
over live sessions. The production move is to store a **SHA-256 hash** of it and look up by hash —
same idea as passwords, and cheap to do since these are already random, so no BCrypt is needed. Left
plain here so you can `SELECT` it and see what is happening; flagged so you know it is a real gap.

## Step 8 — Refresh with rotation

Add to `AuthService`:

```java
    @Transactional
    public AuthResponse refresh(String refreshTokenValue) {
        RefreshToken stored = refreshTokenRepository.findByToken(refreshTokenValue)
                .orElseThrow(() -> new InvalidTokenException("Refresh token not recognised"));

        if (!stored.isUsable()) {
            log.warn("Reuse or expiry detected for refresh token of user {}", stored.getUserId());
            refreshTokenRepository.revokeAllForUser(stored.getUserId());
            throw new InvalidTokenException("Refresh token is no longer valid");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new InvalidTokenException("User no longer exists"));

        stored.revoke();

        List<String> authorities = user.getRoles().stream().map(Role::asAuthority).toList();
        String accessToken = jwtService.generateAccessToken(user.getEmail(), authorities);
        String newRefreshToken = createRefreshToken(user.getId());

        return AuthResponse.of(accessToken, newRefreshToken,
                jwtProperties.accessTokenTtl().toSeconds());
    }
```

**Walk through the security thinking, because this method is mostly security:**

`stored.revoke()` before issuing the new one — that is **rotation**. The old token dies the moment it
is used.

`revokeAllForUser(...)` when an unusable token is presented — that is **reuse detection** (concept
A4). Someone presented a token we issued but already retired. Either an attacker has a copy, or the
legitimate user does. We cannot tell which, so we kill every session for that user. Both parties must
log in again; only one of them can.

**Roles are read fresh from the database here**, not copied from the old token. This is how a
permission change takes effect: promote a user to ADMIN and their *next* refresh — at most 15 minutes
away — carries the new role. Copying old claims forward would mean a demoted admin keeps admin rights
for a week.

`stored.revoke()` needs no explicit `save()`. Inside `@Transactional`, `stored` is a **managed**
entity; Hibernate's dirty checking compares it against its loaded snapshot at flush time and issues
the `UPDATE` itself. Calling `save()` would work but is redundant — and understanding *why* it is
redundant is the difference between using JPA and guessing at it.

**Add the exception** in `.../exception/InvalidTokenException.java`:

```java
package com.eventbooking.auth.exception;

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
```

**And map it** in `GlobalExceptionHandler`:

```java
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidTokenException ex,
                                                       HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiError.of(401, "Unauthorized", ex.getMessage(), request.getRequestURI()));
    }
```

401, not 403: we are saying "your credential is no good", which is an authentication problem. A client
seeing 401 knows to send the user back to login.

## Step 9 — Logout

Add to `AuthService`:

```java
    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByToken(refreshTokenValue)
                .ifPresent(token -> {
                    token.revoke();
                    log.info("Logout for user {}", token.getUserId());
                });
    }
```

**`ifPresent` — no exception on an unknown token.** Logout must be **idempotent**: calling it twice, or
with rubbish, succeeds quietly. A logout endpoint that errors is a logout endpoint that leaves users
stuck in a broken state, and it also tells an attacker which tokens exist.

**Say the honest thing again:** this revokes the *refresh* token. The user's current access token keeps
working until it expires — up to 15 minutes. Concept A4. If a product genuinely cannot accept that
window, it needs a `jti` blacklist in Redis checked on every request, and it is no longer stateless.

## Step 10 — The JWT filter

**File:** `.../config/JwtAuthenticationFilter.java`

The class shell:

```java
package com.eventbooking.auth.config;

import com.eventbooking.auth.application.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }
}
```

**Now the method that does the work.** Add:

```java
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                String email = jwtService.extractEmail(token);
                List<String> roles = jwtService.extractRoles(token);

                var authorities = roles.stream().map(SimpleGrantedAuthority::new).toList();

                var authentication =
                        new UsernamePasswordAuthenticationToken(email, null, authorities);
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException ex) {
                log.debug("Rejected token on {}: {}", request.getRequestURI(), ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        return (header != null && header.startsWith(PREFIX))
                ? header.substring(PREFIX.length())
                : null;
    }
```

**This is the most important method in the service. Line by line:**

**`extends OncePerRequestFilter`** — guaranteed to run exactly once even across forwards and error
dispatches. Twice would mean parsing and verifying the token twice per request.

**`getAuthentication() == null` check** — do not overwrite an identity that an earlier filter already
established. Without it, filter ordering bugs become silent identity bugs.

**`catch (JwtException)` then `chain.doFilter(...)` anyway** — concept A5. A bad token is not an error
here; it simply means *nobody is authenticated*. `AuthorizationFilter` further down decides whether
this endpoint tolerates that. Throwing here would skip `DispatcherServlet` and your
`@RestControllerAdvice` with it.

**`SecurityContextHolder.clearContext()` in the catch** — belt and braces. Never leave a half-built
context behind.

**`credentials = null`** in the token constructor — the raw credential is not kept in memory after use.
Same reasoning as `DaoAuthenticationProvider` wiping it.

**The three-argument constructor is what marks the token authenticated.** `new
UsernamePasswordAuthenticationToken(principal, credentials)` — two arguments — creates an
*unauthenticated* token. The three-argument version, with authorities, sets `authenticated = true`.
Getting this wrong gives you a populated context that still fails authorization, and the cause is not
obvious from the error.

**`chain.doFilter(...)` outside every branch** — the request always continues. Forgetting this returns
an empty 200 and no log line, which is a genuinely horrible bug to chase.

**`log.debug`, not `log.warn`** — invalid tokens are routine (expired ones happen constantly). At warn
level this line would flood your logs. And note that **the token itself is never logged.** A token in a
log file is a live credential in a log file.

## Step 11 — Wire the filter in

**File:** `.../config/SecurityConfig.java`

Take `JwtAuthenticationFilter` in the constructor, then add to the chain:

```java
            .addFilterBefore(jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class)
```

**Before `UsernamePasswordAuthenticationFilter`** — concept A5. Anywhere after `AuthorizationFilter`
and every protected endpoint returns 401 forever, because authorization is decided before anyone is
authenticated.

Note the filter is a `@Component` **and** added explicitly here. That has one side effect worth
knowing: Spring Boot also registers any `Filter` bean with the servlet container automatically, so it
would run twice — once in the container chain, once in the security chain. `OncePerRequestFilter`
saves you from the visible symptoms, but the clean fix is a `FilterRegistrationBean` with
`setEnabled(false)`, or dropping `@Component` and constructing it here. Worth knowing because the
symptom (a filter running outside the security chain) is confusing.

## Step 12 — Proper 401 and 403 bodies

Without this step, an unauthenticated request returns an empty 401 with no body. Usable, but a client
cannot tell *why*.

**File:** `.../config/SecurityExceptionHandlers.java`

```java
package com.eventbooking.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.eventbooking.auth.api.dto.ApiError;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityExceptionHandlers {

    private final ObjectMapper objectMapper;

    public SecurityExceptionHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    ApiError.of(401, "Unauthorized",
                            "Authentication required", request.getRequestURI()));
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, deniedException) -> {
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    ApiError.of(403, "Forbidden",
                            "You do not have permission for this action",
                            request.getRequestURI()));
        };
    }
}
```

**The two names encode the 401/403 distinction from concept A1 in Phase 2a:**

- **`AuthenticationEntryPoint`** runs when there is **no valid identity** → 401. "Here is the entry
  point, go and authenticate."
- **`AccessDeniedHandler`** runs when there **is** an identity but it lacks the authority → 403.

These exist as separate hooks precisely because the two cases are different, and Spring will not let
you conflate them.

`ObjectMapper` is injected rather than created: Spring Boot's instance carries your date format,
naming strategy and modules. A `new ObjectMapper()` here would serialise `Instant` differently from
every other endpoint — a small inconsistency that is annoying to track down.

**Register them** in `SecurityConfig`:

```java
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
```

## Step 13 — Endpoints for refresh, logout, and "who am I"

**File:** `.../api/dto/RefreshRequest.java`

```java
package com.eventbooking.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {
}
```

**Add to `AuthController`:**

```java
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }
```

The refresh token travels in the **body**, not a header. It is not a credential for *this* request —
it is the payload being exchanged. Keeping it out of the `Authorization` header also keeps it out of
proxy access logs, which routinely record headers.

`204 No Content` for logout: it succeeded and there is nothing to say.

**Now the endpoint that proves the filter works.** Change `/login` to return tokens and add `/me`:

```java
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.loginWithTokens(request));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        return ResponseEntity.ok(Map.of(
                "email", authentication.getName(),
                "authorities", authentication.getAuthorities()
        ));
    }
```

**`Authentication authentication` as a parameter — where does it come from?** Not from the request
body. Spring MVC has an argument resolver that pulls it out of `SecurityContextHolder` — the
`ThreadLocal` your filter populated a few milliseconds earlier, on this same thread.

**That parameter is the whole chapter in one line.** Filter → context → thread-local → resolver →
controller. If `/me` returns your email, every piece of it works.

## Step 14 — Method-level security

**File:** `SecurityConfig.java` — add the annotation to the class:

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
```

This switches on `@PreAuthorize` / `@PostAuthorize`. It works through **AOP**: Spring wraps annotated
beans in a proxy that evaluates the expression before the method runs — see the AOP section of the
[filters doc](../concepts/filters-and-request-lifecycle.md), **including the self-invocation trap,
which applies here exactly as it does to `@Transactional`.**

**Add an admin-only endpoint** to `AuthController`:

```java
    @PostMapping("/users/{id}/promote")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> promote(@PathVariable Long id) {
        return ResponseEntity.ok(UserResponse.from(authService.grantAdmin(id)));
    }
```

**And in `AuthService`:**

```java
    @Transactional
    public User grantAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        user.grantRole(Role.ADMIN);
        refreshTokenRepository.revokeAllForUser(userId);

        log.info("Granted ADMIN to user {}", userId);
        return user;
    }
```

**`revokeAllForUser` on a permission change is the subtle, important part.** The user's existing tokens
carry the *old* roles. Forcing a re-login means the new role takes effect immediately rather than
whenever their refresh happens to come round.

The same logic runs in reverse and matters far more: when you **remove** a role, stale tokens mean the
user keeps a permission you believe you revoked. Revoking refresh tokens shrinks that window to the
access-token lifetime. **This is the practical cost of stateless auth, and it is a favourite interview
question.**

`hasRole('ADMIN')` — no `ROLE_` prefix, because `hasRole` adds it. Our authority strings are
`ROLE_ADMIN`, built by `Role.asAuthority()`. One convention, one place. (Phase 2a, concept A2.5.)

**Two things to know about `@PreAuthorize`:**

- It runs **after** the filter chain, so `AuthorizationFilter` has already allowed the request through
  as "authenticated". `@PreAuthorize` is a second, finer gate.
- The expression is a **string**, checked at runtime. A typo — `hasRoles('ADMIN')` — compiles happily
  and blows up on the first call. Test your secured endpoints; the compiler cannot help you here.

## Step 15 — Run the whole flow

Rebuild and restart. Then:

**1. Login and capture the tokens:**

```bash
ACCESS=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}' | jq -r .accessToken)

echo $ACCESS
```

**2. Read your own token** — paste it into jwt.io, or decode the payload locally:

```bash
echo $ACCESS | cut -d. -f2 | base64 -d 2>/dev/null | jq
```

```json
{
  "jti": "…", "sub": "kartick@example.com", "iss": "auth-service",
  "iat": 1735000000, "exp": 1735000900,
  "roles": ["ROLE_USER"], "type": "ACCESS"
}
```

**No key was needed to read that.** Concept A1, proven on your own machine.

**3. Call a protected endpoint:**

```bash
curl -s http://localhost:8081/api/v1/auth/me -H "Authorization: Bearer $ACCESS" | jq
```

```json
{"email":"kartick@example.com","authorities":[{"authority":"ROLE_USER"}]}
```

**4. Without the token:**

```bash
curl -i http://localhost:8081/api/v1/auth/me
```

`401` with your `ApiError` body from the `AuthenticationEntryPoint`.

**5. Hit the admin endpoint as a USER:**

```bash
curl -i -X POST http://localhost:8081/api/v1/auth/users/1/promote \
  -H "Authorization: Bearer $ACCESS"
```

`403` from the `AccessDeniedHandler`. **Authenticated, not authorized** — the whole 401/403 distinction
demonstrated in two curl commands.

---

## Break it on purpose

### Drill 1 — tamper with the payload

Decode the payload, change `"roles":["ROLE_USER"]` to `["ROLE_ADMIN"]`, re-encode, and rebuild the
token with the original header and signature:

```bash
HEAD=$(echo $ACCESS | cut -d. -f1)
SIG=$(echo $ACCESS | cut -d. -f3)
FAKE=$(echo -n '{"sub":"kartick@example.com","roles":["ROLE_ADMIN"],"exp":9999999999}' \
       | base64 | tr '+/' '-_' | tr -d '=')
curl -i http://localhost:8081/api/v1/auth/me -H "Authorization: Bearer $HEAD.$FAKE.$SIG"
```

`401`. The signature was computed over the *original* payload; ours no longer matches. Watch the debug
log: `JWT signature does not match locally computed signature`.

**Do this once with your own hands.** Reading "the signature protects it" is not the same as watching
your forged admin token bounce.

### Drill 2 — expiry

Set `access-token-ttl: PT10S`, restart, log in, wait 15 seconds, call `/me`.

`401`, and the log says `JWT expired`. Then reason it through: with a 15-minute TTL, a stolen token is
useful for at most 15 minutes. With a 24-hour TTL, a day. **The number is your security posture, and
it is one line of config.**

Set it back to `PT15M`.

### Drill 3 — rotation and reuse detection

```bash
REFRESH=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kartick@example.com","password":"Str0ngPass!"}' | jq -r .refreshToken)

# first refresh — works, returns a new pair
curl -s -X POST http://localhost:8081/api/v1/auth/refresh \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$REFRESH\"}" | jq

# same token again — 401, and every session for that user is killed
curl -i -X POST http://localhost:8081/api/v1/auth/refresh \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$REFRESH\"}"
```

Then look at the table:

```bash
docker exec -it ebp-postgres psql -U ebp -d auth_db \
  -c "SELECT id, left(token,8) AS token, revoked FROM refresh_tokens ORDER BY id;"
```

Every row `revoked = true`. **That is reuse detection.** The second use of a rotated token is treated
as evidence of theft, and the response is to log everyone out.

### Drill 4 — logout is not instant

Log in, keep the access token, log out with the refresh token, then call `/me` with the access token.

**It still works.** For up to 15 minutes.

That is not a bug in your code — it is the defining property of stateless authentication (concept A4).
Refresh is dead, so no *new* access tokens can appear, but the one already issued cannot be recalled.
Being able to explain this calmly, with the fix (short TTL, or `jti` blacklist and lose statelessness),
is exactly the level of answer a senior interview is looking for.

### Drill 5 — the wrong token at the wrong endpoint

Send an **access** token where a refresh token is expected:

```bash
curl -i -X POST http://localhost:8081/api/v1/auth/refresh \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$ACCESS\"}"
```

`401` — no such row in `refresh_tokens`. Two independent defences did this: refresh tokens are opaque
UUIDs looked up in a table, and access tokens carry `"type":"ACCESS"`. **Defence in depth**: either
one alone would have held, and that is the point.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Filter added after `AuthorizationFilter` | Every protected endpoint 401s | `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` |
| Throwing from inside the filter | Ugly HTML error page, advice never runs | Catch, clear the context, continue the chain |
| Forgot `chain.doFilter(...)` | Empty 200, no logs | Always continue the chain |
| Two-arg `UsernamePasswordAuthenticationToken` | Context populated but still 403 | Use the three-arg constructor |
| Secret shorter than 32 bytes | `WeakKeyException` at startup | `openssl rand -base64 48` |
| Secret committed to Git | Anyone can mint admin tokens | Environment variable / secret manager |
| No `exp` claim | Tokens valid forever | Always set expiry |
| Secrets inside the payload | Readable by anyone | Only non-sensitive claims |
| Logging the token | Live credentials in log files | Log the subject, never the token |
| `hasRole('ROLE_ADMIN')` | Always 403 | `hasRole` adds the prefix; use `hasAuthority` if you want it explicit |
| Roles copied from the old token on refresh | Demoted users keep their rights | Re-read roles from the database |
| No rotation | A stolen refresh token lasts for weeks | Rotate, and detect reuse |
| `@Modifying` without `clearAutomatically` | Stale entities in the same transaction | Add it when you re-read them |

---

## Production considerations

- **RS256 with a JWKS endpoint** once more than one service verifies tokens (concept A2). Then only
  auth-service can mint.
- **Key rotation.** Keep a `kid` (key id) in the header so you can run two keys at once and retire the
  old one gracefully. Nearly impossible to add later.
- **Hash refresh tokens** before storing them (step 7 note).
- **A cleanup job** for expired refresh rows — that table only grows. A nightly
  `DELETE WHERE expires_at < now()` on the index you created in V2.
- **Rate limit `/refresh` and `/login`**, per IP and per account.
- **Cap concurrent sessions** per user if the product wants it — you have the table for it.
- **Log security events** (login, failed login, refresh reuse, role change) to an audit table, not just
  the application log.
- **HTTPS only.** A bearer token on plain HTTP is a password shouted across the room.
- **Where does the client keep the token?** `localStorage` is readable by any XSS; an httpOnly cookie
  is not, but reintroduces CSRF (concept A4 in Phase 2a). There is no free option — pick and defend.

---

## Phase 2b checklist

- [ ] Login returns `accessToken`, `refreshToken`, `tokenType`, `expiresIn`
- [ ] You decoded your own token payload without any key
- [ ] `/me` returns your email with a valid token, 401 without one
- [ ] A tampered payload is rejected — you tried it
- [ ] An expired token is rejected — you tried it with a 10-second TTL
- [ ] A USER hitting the admin endpoint gets **403**, not 401, and you can say why
- [ ] Refresh rotates the token; reusing the old one revokes every session
- [ ] You know that logout leaves the access token alive for up to 15 minutes, and why
- [ ] You can draw: request → JWT filter → `SecurityContext` → `AuthorizationFilter` →
      `@PreAuthorize` → controller
- [ ] You can explain HS256 vs RS256 and which you would pick for 10 services

---

## What Phase 3 does next

Auth service is complete. But right now the client talks to `localhost:8081` directly — and in a
moment there will be three more ports. That does not scale, and it means every service must implement
authentication for itself.

Next: the **API Gateway**. Routing by service name through Eureka, verifying the JWT once at the edge,
forwarding identity as headers, CORS, rate limiting, and correlation IDs so one request can be traced
across every service.

**Pre-reading for Phase 3:** what a reverse proxy is, and the difference between "authenticate at the
edge" and "authenticate in every service".
