package com.ticketing.orders.consumer.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReservationResponse(String orderId, String eventId, String customerId, String status,
    String resolution, long inventoryVersion, List<String> requestedTicketIds, List<String> activeTicketIds,
    Instant expiresAt) {
}
