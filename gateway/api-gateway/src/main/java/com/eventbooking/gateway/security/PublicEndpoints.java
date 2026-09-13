package com.eventbooking.gateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Map;

public class PublicEndpoints {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> PUBLIC_PATH = List.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/actuator/health"
    );
    private static final List<String> PUBLIC_GET_PATHS = List.of(
            "/api/v1/events",
            "/api/v1/events/*"
    );

    private PublicEndpoints() {}

    public static boolean isPublic(ServerHttpRequest request) {
        String path = request.getURI().getPath();

        if(request.getMethod() == HttpMethod.OPTIONS) {
            return true;
        }

        if(PUBLIC_PATH.stream().anyMatch(p -> MATCHER.match(p, path))) {
            return true;
        }

        return request.getMethod() == HttpMethod.GET
                && PUBLIC_GET_PATHS.stream().anyMatch(p -> MATCHER.match(p, path));
    }
}
