package com.eventbooking.booking.exception;

public class EventNotBookableException extends RuntimeException {
    public EventNotBookableException (String message) {
        super(message);
    }
}
