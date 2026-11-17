package com.ticketing.orders.model.utils;

public final class OrderStates {
    public static final String AVAILABLE = "AVAILABLE";
    public static final String RESERVED = "RESERVED";
    public static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    public static final String SOLD = "SOLD";
    public static final String COMPLIMENTARY = "COMPLIMENTARY";
    public static final String RESERVING = "RESERVING";
    public static final String AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";
    public static final String CONFIRMING = "CONFIRMING";
    public static final String COMPLETED = "COMPLETED";
    public static final String EXPIRED = "EXPIRED";
    public static final String REJECTED = "REJECTED";

    private OrderStates() {
    }
}
