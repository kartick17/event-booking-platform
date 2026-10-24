# Phase 2b — Auth Service: JWT, refresh tokens, RBAC

**Time:** 4–5 hours
**Needs:** Phase 2a finished (register + login working).
**Pre-reading:** paste any JWT into jwt.io and look at the three parts.

At the end of this doc, login returns tokens, protected endpoints demand them, roles are enforced, and
you can explain every step out loud.

---

## In plain English

Phase 2a checked the password. This phase gives the user a token after login, so they do not send the password on every request. It also adds roles, token renewal, and logout.

Think of an access token as a short-lived pass. The application checks the pass on each visit. A refresh token lets the user get a new pass without logging in again. A stolen pass may still work until its expiry time, so keeping that time short matters.

Words used in this phase:

- **JWT:** a token containing readable data and a signature that helps detect changes.
- **Claim:** one piece of data in a token, such as the user's email or expiry time.
- **Signature:** a value calculated with a key to check who issued the token and whether it changed.
- **Access token:** the short-lived token sent to protected endpoints.
- **Refresh token:** the longer-lived token exchanged for a new access token.
- **Rotation:** replacing a refresh token with a new one each time it is used.
- **Revocation:** making a saved token unusable.
- **RBAC:** Role-Based Access Control; roles such as USER and ADMIN decide permissions.
- **Stateless check:** verifying an access token without looking up a saved login session.

Start with the two-token flow in A3. Then follow login, one protected request, refresh, and logout as four separate actions.

---

# PART A — Understand the ideas

## A1. What a JWT actually is

**JWT = JSON Web Token.** In the signed format used here, it has three parts separated by dots. Base64url converts the data into text suitable for a token; it does not hide the data:

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

**Encoding does not hide data.** Anyone with the token can read its payload without knowing the signing key. For example, jwt.io can show the fields in your browser.

So:
- **Never put secrets in a JWT.** No passwords, no card numbers, no personal data you would not print
  on a postcard.
- What stops someone editing `"roles":["ROLE_ADMIN"]` into the payload? **The signature.** Change one
  byte of the payload and the signature no longer matches, because they cannot recompute it without
  the secret key. Verification fails and the token is rejected.

> **How to explain it:** "Our JWT contains readable JSON and a signature. The signature lets us check the issuer and detect changes. It does not keep the contents secret."

### Registered claims worth knowing

| Claim | Meaning | Do we use it |
|---|---|---|
| `sub` | subject — who the token is about | yes, the email |
| `iat` | issued at | yes |
| `exp` | expires at | yes, required |
| `jti` | JWT id, unique per token | yes, needed for revocation |
| `iss` | issuer | yes, `auth-service` |
| `aud` | audience — who it is for | later, if services need different tokens |
| `nbf` | not before | no |

All times are **seconds since epoch, UTC**. Never local time.

## A2. HS256 vs RS256 — a real architecture decision

**HS256 (HMAC + SHA-256)** — **one shared secret** signs and verifies.

**RS256 (RSA)** — a **key pair**: the private key signs, the public key verifies.

The gateway checks tokens, and other services may later check them too. With HS256, every verifier needs the same secret key. Anyone holding that key can also create tokens, so each extra copy of the key adds another place it could be stolen.

With RS256, auth-service keeps the private key used to create signatures. Other services get only the public key used to check signatures. Knowing the public key does not allow them to create valid tokens.

| | HS256 | RS256 |
|---|---|---|
| Keys | one shared secret | private + public |
| Who can create tokens | anyone with the secret | only the private key holder |
| Key distribution | secret to every verifier | public key anywhere |
| Speed | faster | slower to sign |
| Right for | single service, or one trusted verifier | multiple services, third parties |

**We use HS256 to keep this learning project simpler.** The gateway checks the token, and the other services trust its `X-User-Id` header. Phase 3 explains the condition: outside callers must not be able to reach those services directly.

> **How to explain the choice:** "We use HS256 and check tokens at the gateway. If several services needed to verify tokens, I would consider RS256. Auth-service would keep the private key, and a JWKS endpoint would publish the public verification keys."

## A3. Why two tokens

One token lifetime creates a difficult choice. A short lifetime makes users log in often. A long lifetime gives someone more time to use a stolen token. Access and refresh tokens help balance these needs.

Two tokens split the problem:

| | Access token | Refresh token |
|---|---|---|
| Lifetime | 15 minutes | 7 days |
| Sent | on every request | only to `/auth/refresh` |
| Stored server-side | **no** | **yes**, in a table |
| Can be revoked | not really | yes, delete the row |
| Contains | sub, roles, exp | a random random and without readable data id (or a minimal JWT) |

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

The access token is sent often, but it expires after 15 minutes. The refresh token is used less often and is recorded in our database, so we can revoke it. This stops new access tokens from being issued after logout, while existing access tokens can still work until they expire.

## A4. The revocation problem

**A stateless access-token check does not look up a saved session.** How can the server reject a stolen token before its expiry if it does not check any saved revocation information?

The server cannot remove a token from someone else's device. It can only stop accepting it. A fully stateless check will still accept it until expiry, so these are the options:

| Approach | How | Cost |
|---|---|---|
| Short expiry | 15 min, accept the window | free, but a 15-minute hole |
| Refresh token table | delete the row on logout | one DB write; access token still lives up to 15 min |
| Blacklist by `jti` | store revoked ids in Redis until `exp` | a Redis lookup per request — you just gave up statelessness |
| Token version | a counter on the user; tokens carry it; bump it to kill all tokens | a user lookup per request |

**We choose short expiry and a refresh-token table.** Logout makes the stored refresh token unusable, stopping further access tokens from being issued through it. An access token already issued may still work for up to 15 minutes.

**Remember:** logout stops refreshes, but does not immediately cancel an access token that has already been issued. Immediate rejection needs an extra server-side check on requests. That is an extra cost of making logout immediate.

### Refresh token rotation and reuse detection

Every refresh returns a **new** refresh token and invalidates the old one. That is **rotation**.

The reason is clever and worth understanding. Suppose an attacker steals a refresh token:

```
attacker refreshes → gets token B, our row now holds B
victim refreshes with the old token A → A is not valid any more
```

If a refresh token that was already replaced is used again, someone may have copied it. We cannot tell which holder is legitimate. **Revoke all refresh tokens for that user** so both must log in again. This is called **reuse detection**.

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
   request there. Forgetting it makes the request vanish without a clear error with an empty 200.

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

There are three JJWT dependencies. Your code uses `jjwt-api` when it compiles; the other libraries provide the implementation when it runs. This keeps your code from depending on internal library classes. Phase 2a used the same idea with the Postgres driver's `runtime` scope.

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

**Never commit a real signing key to Git.** Anyone who gets that key can create tokens, including admin tokens. The default here is only for local learning. Set a real key through the environment or a secret manager in a real environment.

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

The constructor builds the key once. We reuse it because token verification runs on every request that carries a token; rebuilding the key each time would repeat unnecessary work.

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

- `.id(...)` sets `jti`, a unique token id. It can identify a token in a later revocation list. Tokens already issued cannot have this field added afterwards.
- `.subject(email)` sets `sub`, the user the token describes. The filter uses it when creating `Authentication`.
- `.issuer(...)` sets `iss`, the system that created the token. A verifier can reject a different issuer.
- `.expiration(...)` sets `exp`, the time after which the token must be rejected. Always give access tokens an expiry.
- `"roles"` is our own claim holding permissions. The gateway can check `hasRole('ADMIN')` without asking auth-service on every request.
- `"type": "ACCESS"` identifies the token's purpose. Check the purpose at the receiving endpoint so an access token cannot be exchanged as a refresh token.
- `.compact()` builds and signs the final token string.

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
2. **The signature matches** — recomputed with our key. This is where a changed without permission payload dies.
3. `exp` is in the future.
4. `nbf`, if present, is in the past.
5. `iss` equals ours (because we asked with `requireIssuer`).

Anything wrong throws a subclass of `JwtException`: `ExpiredJwtException`, `SignatureException`,
`MalformedJwtException`.

`clockSkewSeconds(60)` allows a small difference between server clocks. Without this tolerance, a token created on a clock that is slightly ahead might appear not to be valid yet on another server. This example allows 60 seconds.

Use `parseSignedClaims`, not `parseClaimsJwt`. We require a signed token. Some attacks change the token header to `alg: none` and remove the signature; requiring signed claims prevents an unsigned token from being accepted this way.

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
keeps a incorrectly formatted token from becoming a `ClassCastException` deep in the filter.

## Step 5 — The refresh token table

**File:** `.../resources/db/migration/V2__create_refresh_tokens_table.sql`

**V2, not an edit to V1.** Concept A5 in Phase 2a — applied migrations are fixed after creation.

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

- `UNIQUE (token)` prevents duplicate token values and gives token lookups an index. Without an index, a lookup using `WHERE token = ?` may scan the table.
- `idx_..._user_id` helps revoke every refresh token for a user, such as after detecting reuse.
- `idx_..._expires_at` helps the cleanup job find expired rows without scanning everything.
- `revoked BOOLEAN` keeps the record after revocation. That lets the server distinguish an unknown token from a token already used and replaced, which is needed for reuse detection.

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

We store `Long userId` rather than `@ManyToOne User user` because refreshing a token only needs the user id. Loading a whole user object adds extra work and can trigger unexpected database reads. This also prepares you for Phase 6, where a booking refers to an event in another service by id.

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

**Watch out:** a `@Modifying` query changes the database directly. Java entities already loaded in the same transaction may still show the old `revoked` value. If the code reads them again, use `@Modifying(clearAutomatically = true)` so Hibernate does not reuse those old values.

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

Use the authorities already in the **`Authentication` object**. `DaoAuthenticationProvider` loaded them through `UserDetailsService` during `authenticate()`. Reading them again would repeat a query and could give a different answer if roles changed between reads.

**The refresh token is a random UUID, not a JWT.** We check it against a database record, so it does not need to contain user details. The string has no readable claims. It is still a secret credential: whoever holds a usable one can try to refresh.

**For a real system, store a hash of the refresh token.** This example stores plain text so you can inspect it with `SELECT`. That means a stolen database copy exposes usable refresh tokens. A SHA-256 hash is appropriate for these already-random tokens: hash the presented token and look up that hash. Passwords still need a slow password hash such as BCrypt.

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

Calling `revokeAllForUser(...)` for an unusable stored token is the reuse check from A4. It may mean someone copied an old token. We revoke the user's refresh tokens because we cannot safely decide which holder to trust.

**Read roles from the database during refresh.** A new access token should contain current permissions, not copy old roles from an expired token. Otherwise a removed admin role could remain in newly issued tokens.

You do not need to call `save()` after `stored.revoke()`. Inside `@Transactional`, Hibernate tracks changes to this loaded entity and writes them to the database when it flushes. This automatic tracking is called **dirty checking**.

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

**`ifPresent` lets logout succeed even if the token is unknown.** Repeating logout should leave the user logged out rather than produce an error. This is **idempotency**: repeating an action has the same final effect. It also avoids revealing which tokens exist.

**Logout revokes the refresh token.** The current access token can keep working for up to 15 minutes. Rejecting it sooner would need a saved list of revoked `jti` values, checked on every request, so access-token verification would no longer be fully stateless.

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

**Catch `JwtException` and continue with `chain.doFilter(...)`.** A bad token leaves no verified user in the context. Later security checks decide whether the endpoint allows an anonymous request. Throwing here does not go through the controller's `@RestControllerAdvice`.

**`SecurityContextHolder.clearContext()` in the catch** — belt and braces. Never leave a half-built
context behind.

**`credentials = null`** in the token constructor — the raw credential is not kept in memory after use.
Same reasoning as `DaoAuthenticationProvider` wiping it.

**The three-argument constructor marks the token as authenticated.** The two-argument `UsernamePasswordAuthenticationToken(principal, credentials)` represents details that still need checking. The three-argument form includes authorities and sets `authenticated = true`. Using the wrong form leaves the user unverified.

**`chain.doFilter(...)` outside every branch** — the request always continues. Forgetting this returns
an empty 200 and no log line, which is a really horrible bug to chase.

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

**Avoid registering this filter twice.** A `@Component` filter can be registered automatically by the web server and also added to Spring Security here. `OncePerRequestFilter` limits repeated execution, but a cleaner setup disables the server registration with `FilterRegistrationBean` and `setEnabled(false)`, or creates the filter here without `@Component`.

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

Inject Spring Boot's `ObjectMapper` so it uses the same date formatting, naming rules, and JSON modules as other endpoints. Creating `new ObjectMapper()` could format an `Instant` differently.

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

**Where does `Authentication authentication` come from?** Spring reads it from `SecurityContextHolder`, which the filter filled earlier on the same request thread. It does not come from the request body.

The path is: filter identifies the user → security context stores the user → Spring supplies that user to the controller. If `/me` returns your email, those pieces are connected correctly.

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

**Why call `revokeAllForUser` after a permission change?** Existing tokens still contain the old roles. Revoking refresh tokens forces a new login when a new token is needed. Already issued access tokens keep their old roles until expiry; revoking refresh tokens alone does not cancel them immediately.

The same logic runs in reverse and matters far more: when you **remove** a role, out of date tokens mean the
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
your faked admin token bounce.

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

This is the limit of stateless access-token checks from A4. Logout prevents refreshes, but the existing access token remains valid until expiry. Shorter lifetimes reduce that window. A checked list of revoked `jti` values can close it earlier, at the cost of an extra lookup on each request.

### Drill 5 — the wrong token at the wrong endpoint

Send an **access** token where a refresh token is expected:

```bash
curl -i -X POST http://localhost:8081/api/v1/auth/refresh \
  -H "Content-Type: application/json" -d "{\"refreshToken\":\"$ACCESS\"}"
```

`401` — no such row in `refresh_tokens`. Two independent defences did this: refresh tokens are random and without readable data
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
| Secret committed to Git | Anyone can create admin tokens | Environment variable / secret manager |
| No `exp` claim | Tokens valid forever | Always set expiry |
| Secrets inside the payload | Readable by anyone | Only non-sensitive claims |
| Logging the token | Live credentials in log files | Log the subject, never the token |
| `hasRole('ROLE_ADMIN')` | Always 403 | `hasRole` adds the prefix; use `hasAuthority` if you want it explicit |
| Roles copied from the old token on refresh | Demoted users keep their rights | Re-read roles from the database |
| No rotation | A stolen refresh token lasts for weeks | Rotate, and detect reuse |
| `@Modifying` without `clearAutomatically` | Out of date entities in the same transaction | Add it when you re-read them |

---

## Notes for a real running system

- **Consider RS256 when several services verify tokens.** A **JWKS endpoint** publishes verification keys; only auth-service keeps the private signing key.
- **Plan key rotation.** Put a `kid` (key id) in the header so verifiers can choose between an old and a new key during a change.
- **Hash refresh tokens** before saving them, as explained in Step 7.
- **Delete expired refresh rows regularly.** A nightly `DELETE WHERE expires_at < now()` can use the expiry index from V2.
- **Rate limit `/refresh` and `/login`** for each IP address and account.
- **Limit active sessions per user** if the product requires it. The refresh table records those sessions.
- **Keep an audit table** for login failures, refresh-token reuse, and permission changes.
- **Use HTTPS.** Without encrypted transport, someone on the network path may read bearer tokens.
- **Choose client storage carefully.** `localStorage` is readable by injected JavaScript in an XSS attack. An httpOnly cookie cannot be read by JavaScript, but automatically sent cookies need CSRF protection. See Phase 2a.

---

## Phase 2b checklist

- [ ] Login returns `accessToken`, `refreshToken`, `tokenType`, `expiresIn`
- [ ] You decoded your own token payload without any key
- [ ] `/me` returns your email with a valid token, 401 without one
- [ ] A changed without permission payload is rejected — you tried it
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
