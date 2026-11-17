package com.ticketing.inventory.model;

public final class InventoryErrorCodes {
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String TICKETS_UNAVAILABLE = "TICKETS_UNAVAILABLE";
    public static final String CONCURRENT_TRANSITION = "CONCURRENT_TRANSITION";
    public static final String RESERVATION_NOT_FOUND = "RESERVATION_NOT_FOUND";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String INVALID_TRANSITION = "INVALID_TRANSITION";
    public static final String CONFIRMATION_CONFLICT = "CONFIRMATION_CONFLICT";
    public static final String EXPIRY_DEADLINE_MISMATCH = "EXPIRY_DEADLINE_MISMATCH";
    public static final String RESERVATION_NOT_DUE = "RESERVATION_NOT_DUE";
    public static final String INVALID_CAPACITY = "INVALID_CAPACITY";
    public static final String INVALID_PAGE_SIZE = "INVALID_PAGE_SIZE";
    public static final String INVALID_TICKETS = "INVALID_TICKETS";
    public static final String INVALID_DEADLINE = "INVALID_DEADLINE";
    public static final String INVALID_PURCHASE_TYPE = "INVALID_PURCHASE_TYPE";
    public static final String INVALID_COMPLIMENTARY_REASON = "INVALID_COMPLIMENTARY_REASON";
    public static final String INVALID_IDENTIFIER = "INVALID_IDENTIFIER";
    public static final String INVALID_EXECUTION_POLICY = "INVALID_EXECUTION_POLICY";

    private InventoryErrorCodes() {
    }
}
