package com.ticketing.inventory.api.dto.response;

import java.time.Instant;
import java.util.List;

public record ReservationResponse(String orderId, String eventId, String customerId, String status,
    String resolution, long inventoryVersion, List<String> requestedTicketIds, List<String> activeTicketIds,
    Instant requestedAt, Instant expiresAt, String type, String actorId, String reason,
    String confirmationId) {
    public ReservationResponse {
        requestedTicketIds = List.copyOf(requestedTicketIds);
        activeTicketIds = List.copyOf(activeTicketIds);
    }
}
