package com.eventbooking.event.exception;

public class InvalidEventStateException extends RuntimeException{
    public InvalidEventStateException(String message) {
        super(message);
    }
}
