package com.ticketing.inventory.api.dto.response;

public record AvailabilityResponse(String eventId, long capacity, long available, long reserved,
    long pendingConfirmation, long sold, long complimentary) {
}
