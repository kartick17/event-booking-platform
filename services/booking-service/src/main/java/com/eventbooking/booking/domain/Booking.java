package com.eventbooking.booking.domain;

import com.eventbooking.booking.exception.InvalidBookingStateException;
import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_ref", nullable = false, unique = true, length = 20)
    private String bookingRef;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "event_title", nullable = false)
    private String eventTitle;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Booking() {}

    public Booking(String userId, Long eventId, String eventTitle, int quantity, long unitPriceCents) {
        if (quantity < 1 || quantity > 10) {
            throw new InvalidBookingStateException("Quantity must be between 1 and 10");
        }

        this.bookingRef = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.userId = userId;
        this.eventId = eventId;
        this.eventTitle = eventTitle;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
        this.totalCents = unitPriceCents * quantity;
        this.status = BookingStatus.PENDING;
    }

    private void transitionTo(BookingStatus target) {
        if(!status.canTransitionTo(target)) {
            throw new InvalidBookingStateException("Cannot change booking from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public void confirm() {
        transitionTo(BookingStatus.CONFIRMED);
    }

    public void reject() {
        transitionTo(BookingStatus.REJECTED);
    }

    public void cancel(String requestedBy) {
        if(!userId.equals(requestedBy)) {
            throw new InvalidBookingStateException("A Booking can only be cancel by its owner.");
        }
        transitionTo(BookingStatus.CANCELLED);
    }
}
