package com.ticketing.orders.consumer.dto.request;

import java.time.Instant;
import java.util.List;

public record ReservationRequest(String eventId, String customerId, List<String> ticketIds,
    Instant requestedAt, Instant expiresAt, String type, String actorId, String reason) {
}
