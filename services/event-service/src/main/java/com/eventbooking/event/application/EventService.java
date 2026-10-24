package com.eventbooking.event.application;

import com.eventbooking.event.api.dto.CreateEventRequest;
import com.eventbooking.event.api.dto.EventResponse;
import com.eventbooking.event.api.dto.PageResponse;
import com.eventbooking.event.config.CacheConfig;
import com.eventbooking.event.domain.Event;
import com.eventbooking.event.domain.EventStatus;
import com.eventbooking.event.exception.EventNotFoundException;
import com.eventbooking.event.infrastructure.EventRepository;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.EVENT_CACHE, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.EVENT_LIST_CACHE, allEntries = true)
    })
    @Transactional
    public Event update(Long id, CreateEventRequest request) {
        Event event = getById(id);
        event.updateDetails(request.title(), request.description(), request.venue(),
                request.startsAt(), request.priceCents());
        log.info("Updated event id={} - caches evicted", id);
        return event;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.EVENT_CACHE, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.EVENT_LIST_CACHE, allEntries = true)
    })
    @Transactional
    public Event publish(Long id) {
        Event event = getById(id);
        event.publish();
        log.info("Published event id={}", id);
        return event;
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.EVENT_CACHE, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.EVENT_LIST_CACHE, allEntries = true)
    })
    @Transactional
    public Event cancel(Long id) {
        Event event = getById(id);
        event.cancel();
        log.warn("Cancelled event id={} with {} seats booked", id, event.bookedSeats());
        return event;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.EVENT_CACHE, key = "#id")
    public EventResponse getEventResponse(Long id) {
        log.info("Cache miss - loading event {} from database", id);
        return EventResponse.from(getById(id));
    }

    @Cacheable(cacheNames = CacheConfig.EVENT_LIST_CACHE,
            key = "'published:' + #pageable.pageNumber + ':' + #pageable.pageSize")
    @Transactional(readOnly = true)
    public PageResponse<EventResponse> listPublishedCached(Pageable pageable) {
        return PageResponse.from(
                eventRepository.findByStatusAndStartsAtAfter(
                        EventStatus.PUBLISHED, Instant.now(), pageable
                ),
                EventResponse::from
        );
    }

    @Transactional
    public Event reserveSeats(Long eventId, int quantity) {
        Event event = getById(eventId);
        event.reserveSeats(quantity);
        log.info("Reserved {} seats on event {} - {} remaining", quantity, eventId, event.getAvailableSeats());
        return event;
    }

    @Transactional
    public Event releaseSeats(Long eventId, int quantity) {
        Event event = getById(eventId);
        event.releaseSeats(quantity);
        log.info("Released {} seats on event {} - {} remaining", quantity, eventId, event.getAvailableSeats());
        return event;
    }
}
