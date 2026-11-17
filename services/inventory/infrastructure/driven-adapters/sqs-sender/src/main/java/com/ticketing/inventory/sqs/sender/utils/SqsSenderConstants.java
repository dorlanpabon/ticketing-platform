package com.ticketing.inventory.sqs.sender.utils;

public final class SqsSenderConstants {
    public static final String EXPIRY_COMMAND = "ExpireReservation";
    public static final String ORDERS_DESTINATION = "ORDERS";
    public static final String CATALOG_DESTINATION = "CATALOG";
    public static final String EXPIRATIONS_DESTINATION = "EXPIRATIONS";
    public static final String UNKNOWN_DESTINATION = "Unknown outbox destination";
    public static final String OCCURRED_AT = "occurredAt";
    public static final String METRIC = "metric";
    public static final String OUTBOX_AGE_SECONDS = "outbox_age_seconds";
    public static final String VALUE = "value";
    public static final String OUTBOX_AGE_MESSAGE = "Outbox publication age";
    public static final String TYPE = "type";
    public static final String PAYLOAD = "payload";
    public static final String EXPIRES_AT = "expiresAt";

    private SqsSenderConstants() {
    }
}
