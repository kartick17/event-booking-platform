package com.eventbooking.booking.exception;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(String bookingRef) {
        super("Booking not found: " + bookingRef);
    }
}
