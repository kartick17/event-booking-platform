package com.eventbooking.event.domain;

import com.eventbooking.event.exception.InsufficientSeatsException;
import com.eventbooking.event.exception.InvalidEventStateException;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "available_seats", nullable = false)
    private int availableSeats;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Event() {
    }

    public Event(String title, String description, String venue, Instant startsAt,
                 int capacity, long priceCents, String createdBy) {
        if (capacity <= 0) {
            throw new InvalidEventStateException("Capacity must be greater than zero");
        }
        if (startsAt.isBefore(Instant.now())) {
            throw new InvalidEventStateException("Event cannot start in the past");
        }

        this.title = title;
        this.description = description;
        this.venue = venue;
        this.startsAt = startsAt;
        this.capacity = capacity;
        this.availableSeats = capacity;
        this.priceCents = priceCents;
        this.createdBy = createdBy;
        this.status = EventStatus.DRAFT;
    }

    public void publish() {
        transitionTo(EventStatus.PUBLISHED);
    }

    public void cancel() {
        transitionTo(EventStatus.CANCELLED);
    }

    public void complete() {
        transitionTo(EventStatus.COMPLETED);
    }

    private void transitionTo(EventStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidEventStateException(
                    "Cannot change status from " + status + " to " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public void reserveSeats(int quantity) {
        if(quantity <= 0) {
            throw new InvalidEventStateException("Quantity must be positive");
        }
        if(!status.acceptsBookings()) {
            throw new InvalidEventStateException("Event is not open for booking: " + status);
        }
        if(quantity > availableSeats) {
            throw new InsufficientSeatsException(id, quantity, availableSeats);
        }

        this.availableSeats -= quantity;
        this.updatedAt = Instant.now();
    }

    public void releaseSeats(int quantity) {
        if(quantity <= 0) {
            throw new InvalidEventStateException("Quantity must be positive");
        }

        this.availableSeats = Math.min(capacity, availableSeats + quantity);
        this.updatedAt = Instant.now();
    }

    public void updateDetails(String title, String description, String venue,
                                Instant startsAt, long priceCents) {
        if (status == EventStatus.CANCELLED || status == EventStatus.COMPLETED) {
            throw new InvalidEventStateException("Cannot edit a " + status + " event");
        }

        this.title = title;
        this.description = description;
        this.venue = venue;
        this.startsAt = startsAt;
        this.priceCents = priceCents;
        this.updatedAt = Instant.now();
    }

    public int bookedSeats() {
        return capacity - availableSeats;
    }

    public boolean isSoldOut() {
        return availableSeats == 0;
    }
}