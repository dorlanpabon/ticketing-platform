package com.ticketing.orders.model.utils;

public final class ValidationRules {
    public static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9_.:-]{8,128}";
    public static final String IDENTIFIER_PATTERN = "[A-Za-z0-9:_-]{1,128}";
    public static final String TICKET_ID_PATTERN = "[1-9][0-9]{0,5}";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String HASH_UNAVAILABLE = "SHA-256 unavailable";
    public static final int MAX_TICKETS = 10;
    public static final int MAX_TICKET_ID = 100000;
    public static final int MAX_REASON_LENGTH = 500;

    private ValidationRules() {
    }
}
