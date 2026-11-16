package com.ticketing.inventory.model;

import java.time.Duration;

public final class InventoryRules {
    public static final String NO_RESOLUTION = "NONE";
    public static final String PURCHASE_TYPE = "PURCHASE";
    public static final String IDENTIFIER_PATTERN = "[A-Za-z0-9:_-]{1,200}";
    public static final String TICKET_NUMBER_PATTERN = "[1-9][0-9]{0,5}";
    public static final int MAX_TICKETS_PER_RESERVATION = 10;
    public static final int MAX_EVENT_CAPACITY = 100_000;
    public static final int MAX_TICKETS_PER_PAGE = 100;
    public static final int MAX_COMPLIMENTARY_REASON_LENGTH = 500;
    public static final Duration MAX_RESERVATION_DURATION = Duration.ofSeconds(600);
    public static final Duration MAX_REQUEST_TIME_SKEW = Duration.ofSeconds(2);
    public static final Duration RECOVERY_SWEEP_BUDGET = Duration.ofSeconds(45);

    private InventoryRules() {
    }
}
