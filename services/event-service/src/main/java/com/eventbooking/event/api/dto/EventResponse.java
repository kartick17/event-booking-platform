package com.eventbooking.event.api.dto;

import com.eventbooking.event.domain.Event;
import com.eventbooking.event.domain.EventStatus;

import java.time.Instant;

public record EventResponse(
        Long id,
        String title,
        String description,
        String venue,
        Instant startsAt,
        int capacity,
        int availableSeats,
        int bookedSeats,
        boolean soldOut,
        long priceCents,
        EventStatus status,
        Instant createdAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(), event.getTitle(), event.getDescription(), event.getVenue(),
                event.getStartsAt(), event.getCapacity(), event.getAvailableSeats(),
                event.bookedSeats(), event.isSoldOut(), event.getPriceCents(),
                event.getStatus(), event.getCreatedAt());
    }
}