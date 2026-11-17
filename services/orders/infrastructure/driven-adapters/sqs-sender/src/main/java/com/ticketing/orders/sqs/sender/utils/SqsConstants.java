package com.ticketing.orders.sqs.sender.utils;

public final class SqsConstants {
    public static final String MESSAGE_ID = "messageId";
    public static final String OUTBOX_POLL_INTERVAL = "${OUTBOX_POLL_INTERVAL_MS:500}";
    public static final String WORKERS_ENABLED = "workers.enabled";
    public static final String METRIC_VALUE = "value";
    public static final String ENABLED = "true";
    public static final String OUTBOX_AGE_SECONDS = "outbox_age_seconds";
    public static final String METRIC = "metric";
    public static final String PROPERTIES_PREFIX = "adapter.sqs";

    private SqsConstants() {
    }
}
