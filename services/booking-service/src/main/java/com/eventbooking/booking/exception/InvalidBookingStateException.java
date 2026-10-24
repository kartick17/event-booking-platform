package com.eventbooking.booking.exception;

public class InvalidBookingStateException extends RuntimeException {

    public InvalidBookingStateException(String message) {
        super(message);
    }
}
