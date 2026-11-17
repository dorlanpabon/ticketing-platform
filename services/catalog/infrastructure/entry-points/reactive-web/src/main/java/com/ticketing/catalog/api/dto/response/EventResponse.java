package com.ticketing.catalog.api.dto.response;

import java.time.Instant;

public record EventResponse(String eventId, String name, String venue, Instant startsAt, int capacity,
    String status) {
}
