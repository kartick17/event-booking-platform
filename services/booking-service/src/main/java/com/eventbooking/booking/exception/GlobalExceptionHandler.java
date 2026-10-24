package com.eventbooking.booking.exception;

import com.eventbooking.booking.api.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({
            BookingNotFoundException.class,
            EventNotFoundException.class
    })
    public ResponseEntity<ApiError> handleNotFound(
            RuntimeException ex, HttpServletRequest request) {

        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({
            InvalidBookingStateException.class,
            EventNotBookableException.class,
            SeatsUnavailableException.class
    })
    public ResponseEntity<ApiError> handleConflict(
            RuntimeException ex, HttpServletRequest request) {

        return error(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(EventServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(
            EventServiceUnavailableException ex,
            HttpServletRequest request) {

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(ApiError.of(
                        503,
                        "Service Unavailable",
                        "Event service is temporarily unavailable",
                        request.getRequestURI()));
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> error(
            HttpStatus status, String message, HttpServletRequest request
    ) {
        return ResponseEntity.status(status).body(
                ApiError.of(status.value(),
                        status.getReasonPhrase(),
                        message,
                        request.getRequestURI()
                )
        );
    }
}
