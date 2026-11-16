package com.ticketing.orders.model.utils;

public final class ErrorCodes {
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    public static final String INVALID_IDENTIFIER = "INVALID_IDENTIFIER";
    public static final String INVALID_TICKETS = "INVALID_TICKETS";
    public static final String INVENTORY_STATE_MISSING = "INVENTORY_STATE_MISSING";
    public static final String ORDER_ENQUEUE_UNAVAILABLE = "ORDER_ENQUEUE_UNAVAILABLE";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String RESERVATION_NOT_READY = "RESERVATION_NOT_READY";
    public static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    public static final String UNSUPPORTED_MESSAGE = "UNSUPPORTED_MESSAGE";
    public static final String INVENTORY_IDENTITY_MISMATCH = "INVENTORY_IDENTITY_MISMATCH";
    public static final String INVALID_INVENTORY_TRANSITION = "INVALID_INVENTORY_TRANSITION";
    public static final String INVALID_INVENTORY_STATE = "INVALID_INVENTORY_STATE";

    private ErrorCodes() {
    }
}
