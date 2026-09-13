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