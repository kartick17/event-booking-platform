package com.eventbooking.event.exception;

public class InsufficientSeatsException extends RuntimeException {

    private final Long eventId;
    private final int requested;
    private final int available;

    public InsufficientSeatsException(Long eventId, int requested, int available) {
        super("Event %d has %d seats left, %d requested".formatted(eventId, available, requested));
        this.eventId = eventId;
        this.requested = requested;
        this.available = available;
    }

    public Long getEventId() {
        return eventId;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }

}
