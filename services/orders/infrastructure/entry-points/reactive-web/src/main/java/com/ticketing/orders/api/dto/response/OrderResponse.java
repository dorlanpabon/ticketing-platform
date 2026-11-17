package com.ticketing.orders.api.dto.response;

import java.time.Instant;
import java.util.List;

public record OrderResponse(String orderId, String eventId, String customerId,
    List<String> requestedTicketIds, List<String> activeTicketIds, String status, String workflowStatus,
    String resolution, long inventoryVersion, Instant requestedAt, Instant expiresAt, String type,
    String actorId, String reason) {
}
