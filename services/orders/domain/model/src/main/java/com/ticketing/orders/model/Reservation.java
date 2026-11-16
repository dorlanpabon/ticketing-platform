package com.ticketing.orders.model;

import com.ticketing.orders.model.utils.OrderStates;

import java.time.Instant;
import java.util.List;

public record Reservation(String orderId, String eventId, String customerId, String status, String resolution,
    long inventoryVersion, List<String> requestedTicketIds, List<String> activeTicketIds, Instant expiresAt) {
    public static final String AVAILABLE = OrderStates.AVAILABLE;
    public static final String RESERVED = OrderStates.RESERVED;
    public static final String PENDING_CONFIRMATION = OrderStates.PENDING_CONFIRMATION;
    public static final String SOLD = OrderStates.SOLD;
    public static final String COMPLIMENTARY = OrderStates.COMPLIMENTARY;

    public Reservation {
        requestedTicketIds = List.copyOf(requestedTicketIds);
        activeTicketIds = List.copyOf(activeTicketIds);
    }
}
