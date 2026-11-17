package com.ticketing.inventory.api.dto.request;

import java.time.Instant;
import java.util.List;

public record ReserveTicketsRequest(String orderId, String eventId, String customerId, List<String> ticketIds,
    Instant requestedAt, Instant expiresAt, String type, String actorId, String reason) {
    public ReserveTicketsRequest {
        if (ticketIds != null) {
            ticketIds = List.copyOf(ticketIds);
        }
    }
}
