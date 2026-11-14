package com.ticketing.inventory.model;

public enum TicketStatus {
    AVAILABLE, RESERVED, PENDING_CONFIRMATION, SOLD, COMPLIMENTARY;

    public boolean isHeld() {
        return this == RESERVED || this == PENDING_CONFIRMATION;
    }

    public boolean isFinal() {
        return this == SOLD || this == COMPLIMENTARY;
    }
}
