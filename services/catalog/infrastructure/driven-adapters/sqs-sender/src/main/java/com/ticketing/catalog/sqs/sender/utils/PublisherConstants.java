package com.ticketing.catalog.sqs.sender.utils;

public final class PublisherConstants {
    public static final String MESSAGE_ID_KEY = "messageId";
    public static final String METRIC_KEY = "metric";
    public static final String METRIC_VALUE_KEY = "value";
    public static final String OUTBOX_AGE_METRIC = "outbox_age_seconds";
    public static final String MESSAGE_SENT_LOG = "Message sent";
    public static final String OUTBOX_ATTEMPT_LOG = "Outbox publication attempt";

    private PublisherConstants() {
    }
}
