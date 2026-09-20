package com.eventbooking.event.application;

import com.eventbooking.event.api.dto.CreateEventRequest;
import com.eventbooking.event.api.dto.EventResponse;
import com.eventbooking.event.domain.Event;
import com.eventbooking.event.domain.EventStatus;
import com.eventbooking.event.exception.EventNotFoundException;
import com.eventbooking.event.infrastructure.EventRepository;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@AllArgsConstructor
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private final EventRepository eventRepository;

    @Transactional
    public Event create(CreateEventRequest request, String createdBy) {
        Event event = new Event(
                request.title(),
                request.description(),
                request.venue(),
                request.startsAt(),
                request.capacity(),
                request.priceCents(),
                createdBy
        );

        Event saved = eventRepository.save(event);
        log.info("Created event id={} title='{}' by={}", saved.getId(), saved.getTitle(), createdBy);
        return saved;
    }

    @Transactional
    public Event getById(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new EventNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Event> listPublished(Pageable pageable) {
        return eventRepository.findByStatusAndStartsAtAfter(
                EventStatus.PUBLISHED, Instant.now(), pageable);
    }

    @Transactional(readOnly = true)
    public Page<Event> listAll(Pageable pageable) {
        return eventRepository.findAll(pageable);
    }

    @Transactional
    public Event update(Long id, CreateEventRequest request) {
        Event event = getById(id);
        event.updateDetails(request.title(), request.description(), request.venue(),
                request.startsAt(), request.priceCents());
        log.info("Updated event id={}", id);
        return event;
    }

    @Transactional
    public Event publish(Long id) {
        Event event = getById(id);
        event.publish();
        log.info("Published event id={}", id);
        return event;
    }

    @Transactional
    public Event cancel(Long id) {
        Event event = getById(id);
        event.cancel();
        log.warn("Cancelled event id={} with {} seats booked", id, event.bookedSeats());
        return event;
    }
}
