package com.ticketing.orders.model;

import java.time.Instant;
import java.util.List;
public record Reservation(String orderId,String eventId,String customerId,String status,String resolution,
                          long inventoryVersion,List<String> requestedTicketIds,List<String> activeTicketIds,Instant expiresAt) {
    public Reservation { requestedTicketIds=List.copyOf(requestedTicketIds); activeTicketIds=List.copyOf(activeTicketIds); }
}
