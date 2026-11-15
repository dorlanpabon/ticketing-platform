package com.ticketing.inventory.model;

public record Availability(String eventId, long capacity, long available, long reserved,
        long pendingConfirmation, long sold, long complimentary) {
    public Availability {
        if (capacity != available + reserved + pendingConfirmation + sold + complimentary
                || available < 0 || reserved < 0 || pendingConfirmation < 0 || sold < 0 || complimentary < 0) {
            throw new InventoryException("INVENTORY_INVARIANT_VIOLATION", 503);
        }
    }
}
