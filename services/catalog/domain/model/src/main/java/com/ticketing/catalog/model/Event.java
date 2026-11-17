package com.ticketing.catalog.model;

import java.time.Instant;

public record Event(String eventId, String name, String venue, Instant startsAt, int capacity,
    String status) {
    public static final String PROVISIONING = "PROVISIONING";
    public static final String ACTIVE = "ACTIVE";

    public Event activate() {
        return new Event(eventId, name, venue, startsAt, capacity, ACTIVE);
    }
}
