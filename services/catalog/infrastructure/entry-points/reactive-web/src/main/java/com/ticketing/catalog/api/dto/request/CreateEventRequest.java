package com.ticketing.catalog.api.dto.request;

import java.time.Instant;

public record CreateEventRequest(String name, String venue, Instant startsAt, int capacity) {
}
