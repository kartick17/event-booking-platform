package com.eventbooking.event.infrastructure;

import com.eventbooking.event.domain.Event;
import com.eventbooking.event.domain.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

public interface EventRepository extends JpaRepository<Event, Long> {

    Page<Event> findByStatus(EventStatus status, Pageable pageable);

    Page<Event> findByStatusAndStartsAtAfter(EventStatus status, Instant after, Pageable pageable);
}