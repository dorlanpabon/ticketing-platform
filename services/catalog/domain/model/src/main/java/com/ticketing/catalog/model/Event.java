package com.ticketing.catalog.model;

import java.time.Instant;
public record Event(String eventId, String name, String venue, Instant startsAt, int capacity, String status) {
    public Event activate() { return new Event(eventId,name,venue,startsAt,capacity,"ACTIVE"); }
}
