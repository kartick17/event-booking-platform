package com.eventbooking.booking.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventDto(
        Long id,
        String title,
        Instant startsAt,
        int availableSeats,
        long priceCents,
        String status
) {
    public boolean isBookable() {
        return "PUBLISHED".equals(status) && startsAt.isAfter(Instant.now());
    }

    public boolean hasSeats(int quantity) {
        return availableSeats >= quantity;
    }
}
