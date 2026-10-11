package com.eventbooking.event.api;

import com.eventbooking.event.api.dto.CreateEventRequest;
import com.eventbooking.event.api.dto.EventResponse;
import com.eventbooking.event.api.dto.PageResponse;
import com.eventbooking.event.application.EventService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/api/v1/events")
public class EventController {
    private final EventService eventService;

    @GetMapping()
    public PageResponse<EventResponse> list (
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.ASC)
            Pageable pageable
    ) {
        log.info("Get all events");
        return PageResponse.from(eventService.listAll(pageable), EventResponse::from);
    }

    @GetMapping("/{id}")
    public EventResponse getOne(@PathVariable Long id) {
        log.info("Hello from event");
        return eventService.getEventResponse(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request,
                                                Authentication authentication) {
        var event = eventService.create(request, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(EventResponse.from(event));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EventResponse update(@PathVariable Long id,
                                @Valid @RequestBody CreateEventRequest request) {
        return EventResponse.from(eventService.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public EventResponse publish(@PathVariable Long id) {
        return EventResponse.from(eventService.publish(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public EventResponse cancel(@PathVariable Long id) {
        return EventResponse.from(eventService.cancel(id));
    }
}
