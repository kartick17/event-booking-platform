package com.eventbooking.event.api.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;

public record CreateEventRequest(

        @NotBlank @Size(max = 200)
        String title,

        @Size(max = 5000)
        String description,

        @NotBlank @Size(max = 200)
        String venue,

        @NotNull @Future
        Instant startsAt,

        @Min(1) @Max(100_000)
        int capacity,

        @PositiveOrZero
        long priceCents
) {
}