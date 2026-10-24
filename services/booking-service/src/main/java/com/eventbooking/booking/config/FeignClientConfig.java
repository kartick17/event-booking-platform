package com.eventbooking.booking.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class FeignClientConfig {

    @Bean
    public RequestInterceptor propagationInterceptor() {
        return template -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if(!(attributes instanceof ServletRequestAttributes servletAttributes)) {
                return;
            }
            var request = servletAttributes.getRequest();
            copyHeader(template, request.getHeader("X-Correlation-Id"), "X-Correlation-Id");
            copyHeader(template, request.getHeader("X-User-Id"), "X-User-Id");
            copyHeader(template, request.getHeader("X-User-Roles"), "X-User-Roles");
        };
    }

    private void copyHeader(feign.RequestTemplate template, String value, String name) {
        if (value != null && !value.isBlank()) {
            template.header(name, value);
        }
    }
}
