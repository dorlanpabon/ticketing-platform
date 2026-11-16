package com.ticketing.orders.sqs.listener.dto;

import java.time.Instant;
import java.util.List;

public record ReservationStatePayload(String orderId, String eventId, String customerId, String status,
    String resolution, long inventoryVersion, List<String> requestedTicketIds, List<String> activeTicketIds,
    Instant expiresAt) {
    public ReservationStatePayload {
        requestedTicketIds = List.copyOf(requestedTicketIds);
        activeTicketIds = List.copyOf(activeTicketIds);
    }
}
