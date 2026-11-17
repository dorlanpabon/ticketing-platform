package com.ticketing.inventory.sqs.listener.dto;

import java.time.Instant;
import java.util.List;

public record ReservationPayload(String orderId, String eventId, String customerId, List<String> ticketIds,
    Instant requestedAt, Instant expiresAt, String type, String actorId, String reason) {
    public ReservationPayload {
        ticketIds = ticketIds == null ? List.of() : List.copyOf(ticketIds);
    }
}
