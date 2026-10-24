package com.eventbooking.booking.exception;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException (String message) {
        super(message);
    }
}
