package com.eventbooking.event.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "cache.events")
public record CacheProperties(Duration ttl, Duration listTtl) {
}
