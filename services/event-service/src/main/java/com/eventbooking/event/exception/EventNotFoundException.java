package com.eventbooking.event.exception;

public class EventNotFoundException extends RuntimeException {
    public EventNotFoundException(Long id) {

        super("Event not found: " + id);
    }
}
