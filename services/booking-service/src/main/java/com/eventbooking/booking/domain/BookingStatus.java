package com.eventbooking.booking.domain;

import java.util.EnumSet;

public enum BookingStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    REJECTED;

    public boolean canTransitionTo(BookingStatus target) {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, REJECTED, CANCELLED).contains(target);
            case CONFIRMED -> target == CANCELLED;
            case CANCELLED, REJECTED -> false;
        };
    }

    public boolean isFinal() {
        return this == CANCELLED || this == REJECTED;
    }
}
