package com.eventbooking.booking.exception;

public class SeatsUnavailableException extends RuntimeException {

    public SeatsUnavailableException (String message) {
        super(message);
    }
}
