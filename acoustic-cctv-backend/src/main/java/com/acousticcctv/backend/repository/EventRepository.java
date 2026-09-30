package com.acousticcctv.backend.repository;

import com.acousticcctv.backend.model.Event;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for events, keyed by event id.
 *
 * NOTE: deliberately not a JPA repository yet — this is module 2's skeleton.
 * Once the pipeline is validated end-to-end on the laptop, swap this class's
 * internals for Spring Data JPA + H2/Postgres without touching any controller
 * or service code, since they only depend on this interface's method
 * signatures. That's the whole point of isolating it behind a repository.
 */
@Repository
public class EventRepository {

    private final Map<String, Event> store = new ConcurrentHashMap<>();

    public Event save(Event event) {
        store.put(event.getId(), event);
        return event;
    }

    public Optional<Event> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Event> findAll() {
        List<Event> all = new ArrayList<>(store.values());
        all.sort(Comparator.comparing(Event::getDetectedAt).reversed());
        return all;
    }
}
