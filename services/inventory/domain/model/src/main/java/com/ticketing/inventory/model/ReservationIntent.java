package com.ticketing.inventory.model;

import java.time.Instant;
import java.util.List;

public record ReservationIntent(String orderId, String eventId, String customerId, List<String> ticketIds,
    Instant requestedAt, Instant expiresAt, String type, String actorId, String reason) {
    public ReservationIntent {
        ticketIds = ticketIds == null ? List.of() : List.copyOf(ticketIds);
        type = type == null ? "PURCHASE" : type;
    }

    public boolean complimentary() {
        return "COMPLIMENTARY".equals(type);
    }
}
