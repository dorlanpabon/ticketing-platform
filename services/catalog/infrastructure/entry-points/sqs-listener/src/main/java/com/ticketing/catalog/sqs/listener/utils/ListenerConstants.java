package com.ticketing.catalog.sqs.listener.utils;

public final class ListenerConstants {
    public static final String MESSAGE_ID_KEY = "messageId";
    public static final String CAUSE_TYPE_KEY = "causeType";
    public static final String MESSAGE_COUNT_KEY = "messageCount";
    public static final String MESSAGE_RETRY_LOG = "Message retained for retry";
    public static final String MESSAGES_RECEIVED_LOG = "Messages received from SQS";
    public static final String OPERATION_NAME = "async_operation";
    public static final String OPERATION_TAG = "operation";
    public static final String OPERATION_PREFIX = "MessageFrom:";
    public static final String SCHEDULER_NAME = "sqs-pool";

    private ListenerConstants() {
    }
}
