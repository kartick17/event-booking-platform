package com.eventbooking.auth.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/ping")
public class PingController {

    @Value("${server.port}")
    private String port;

    @GetMapping
    public Map<String, String> ping() {
        return Map.of(
                "service", "auth-service",
                "port", port,
                "status", "alive"
        );
    }
}
