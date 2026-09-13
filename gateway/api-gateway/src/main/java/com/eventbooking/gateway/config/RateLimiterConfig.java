package com.eventbooking.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver userOrIpKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if(userId != null) {
                return Mono.just("user: " + userId);
            }

            String ip = Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(add -> add.getAddress().getHostAddress())
                    .orElse("unknown");
            return Mono.just("ip: " + ip);
        };
    }
}
