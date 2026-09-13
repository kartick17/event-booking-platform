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

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

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
        if(token == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }

        try {
            Claims claims = jwtValidator.validate(token);

            if(!"ACCESS".equals(claims.get("type", String.class))) {
                return reject(exchange, HttpStatus.UNAUTHORIZED, "Wrong token type");
            }

            ServerHttpRequest enriched = sanitised.mutate()
                    .header(USER_ID_HEADER, claims.getSubject())
                    .header(USER_ROLES_HEADER, String.join(",", roles(claims)))
                    .build();

            return chain.filter(exchange.mutate().request(enriched).build());
        }
        catch (JwtException ex) {
            log.debug("Rejected token for {}: {}", request.getURI().getPath(), ex.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
    }

    private String extractToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        return (header != null && header.startsWith("Bearer "))
                ? header.substring(7)
                : null;
    }

    private List<String> roles(Claims claims) {
        Object raw = claims.get("roles");
        return raw instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        String body = """
                {"status":%d,"error":"%s","message":"%s"}
                """.formatted(status.value(), status.getReasonPhrase(), message);
        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
