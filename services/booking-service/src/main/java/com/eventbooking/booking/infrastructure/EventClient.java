package com.eventbooking.booking.infrastructure;

import com.eventbooking.booking.config.FeignClientConfig;
import com.eventbooking.booking.infrastructure.dto.EventDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "event-service",
        configuration = FeignClientConfig.class
)
public interface EventClient {
    @GetMapping("/api/v1/events/{id}")
    EventDto getEvent(@PathVariable("id") Long id);

    @PostMapping("/api/v1/events/{id}/reserve")
    EventDto reserveSeats(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);

    @PostMapping("/api/v1/events/{id}/release")
    EventDto releaseSeats(@PathVariable("id") Long id, @RequestParam("quantity") int quantity);
}
