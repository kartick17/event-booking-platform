package com.eventbooking.event.domain;

import java.util.EnumSet;
import java.util.Set;

public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED,
    COMPLETED;

    public boolean canTransitionTo(EventStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<EventStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> EnumSet.of(PUBLISHED, CANCELLED);
            case PUBLISHED -> EnumSet.of(CANCELLED, COMPLETED);
            case CANCELLED, COMPLETED -> EnumSet.noneOf(EventStatus.class);
        };
    }

    public boolean acceptsBookings() {
        return this == PUBLISHED;
    }
}
