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