package com.eventbooking.auth.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties (
        @NotBlank String secret,
        @NotBlank String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
        ){
}
