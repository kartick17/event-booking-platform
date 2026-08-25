# Concept — `application.yml` vs `application.properties`

Spring Boot reads both. Same properties, same binding, same `@ConfigurationProperties`. Only the syntax
differs. Spring Initializr (and IntelliJ's New Project wizard) generates `.properties` by default — the
docs in this repo use YAML, so rename the file and rewrite the content.

## Why these docs use YAML

This project's configuration is list-heavy and deeply nested. Gateway routes:

```properties
spring.cloud.gateway.routes[0].id=auth-service
spring.cloud.gateway.routes[0].uri=lb://auth-service
spring.cloud.gateway.routes[0].predicates[0]=Path=/api/v1/auth/**
spring.cloud.gateway.routes[0].filters[0].name=RequestRateLimiter
spring.cloud.gateway.routes[1].id=event-service
```

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://auth-service
          predicates:
            - Path=/api/v1/auth/**
        - id: event-service
          uri: lb://event-service
```

Indexed keys are easy to misnumber and painful to reorder. Resilience4j and Flyway config have the same
shape.

## Comparison

| | `.properties` | `.yml` |
|---|---|---|
| Nested config | prefix repeated on every line | indentation, no repeats |
| Lists | `key[0]`, `key[1]` | `- item` |
| Profiles in one file | separate files needed | `---` document separator |
| Whitespace bugs | impossible | tabs break it; indentation matters |
| Git merge conflicts | clean, line-based | messier |
| Encoding | ISO-8859-1 by default; non-ASCII needs escapes | UTF-8 |
| Type surprises | none — everything is a string | `no` / `off` / `yes` parse as booleans |

**The YAML type trap is real.** Norway's country code:

```yaml
country: NO      # becomes boolean false
country: "NO"    # correct
```

Same for `1.20` (a number — the trailing zero is lost) and ids beginning with `0` (read as octal).
**Quote anything that is not obviously a word.**

## Which for production

**Mostly neither**, because the values that matter do not live in the file:

```yaml
jwt:
  secret: ${JWT_SECRET}
spring:
  datasource:
    url: ${DB_URL}
```

Real values arrive as environment variables or from a secret manager. The file holds structure and
defaults. Both formats read environment variables identically through relaxed binding:
`spring.datasource.url` ← `SPRING_DATASOURCE_URL`.

**For this project: YAML**, because Spring Cloud config is nesting-heavy and every Spring Cloud doc you
will read is written in YAML. `.properties` remains a valid choice for flat, small configuration.

## The gotcha

**Never keep both files.** With `application.properties` and `application.yml` in the same location,
`.properties` wins for any duplicate key. You edit the YAML, restart, nothing changes — and there is no
warning.

After renaming, confirm:

```bash
ls src/main/resources/
```

## Converting

Each dot becomes one indent level. **Two spaces per level, never tabs.**

```properties
spring.application.name=auth-service
server.port=8081
spring.datasource.url=jdbc:postgresql://localhost:5432/auth_db
```

```yaml
spring:
  application:
    name: auth-service
  datasource:
    url: jdbc:postgresql://localhost:5432/auth_db

server:
  port: 8081
```
