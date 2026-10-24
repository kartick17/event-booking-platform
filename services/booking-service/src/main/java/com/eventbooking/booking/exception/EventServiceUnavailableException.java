package com.eventbooking.booking.exception;

public class EventServiceUnavailableException extends RuntimeException {
    public EventServiceUnavailableException(String message) {
        super(message);
    }
}
