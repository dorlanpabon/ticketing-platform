package com.ticketing.orders.sqs.listener.utils;

public final class SqsConstants {
    public static final String MESSAGE_ID = "messageId";
    public static final String ERROR_TYPE = "cause";
    public static final String MESSAGE_COUNT = "messageCount";
    public static final String WORKERS_ENABLED = "workers.enabled";
    public static final String ENABLED = "true";
    public static final String RECONCILIATION_INTERVAL = "${RECONCILIATION_INTERVAL_MS:5000}";
    public static final String PROPERTIES_PREFIX = "entrypoint.sqs";
    public static final String SCHEDULER_NAME = "sqs-pool";
    public static final String OPERATION_PREFIX = "MessageFrom:";
    public static final String ASYNC_OPERATION = "async_operation";
    public static final String OPERATION_TAG = "operation";

    private SqsConstants() {
    }
}
