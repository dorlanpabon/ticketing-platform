package com.ticketing.inventory.sqs.listener.utils;

public final class SqsListenerConstants {
    public static final String MESSAGE_ID = "messageId";
    public static final String EXPIRE_RESERVATION = "ExpireReservation";
    public static final String ORDER_ID = "orderId";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String INVALID_MESSAGE_SCHEMA = "INVALID_MESSAGE_SCHEMA";
    public static final String PAYLOAD = "payload";
    public static final String TYPE = "type";
    public static final String PROVISION_INVENTORY = "ProvisionInventory";
    public static final String EVENT_CREATED = "EventCreated";
    public static final String EVENT_ID = "eventId";
    public static final String CAPACITY = "capacity";
    public static final String PURCHASE_REQUESTED = "PurchaseRequested";
    public static final String COMPLIMENTARY_REQUESTED = "ComplimentaryRequested";
    public static final String EXPIRES_AT = "expiresAt";
    public static final String SWEEP_EXPIRED_RESERVATIONS = "SweepExpiredReservations";
    public static final String CONFIRM_PURCHASE = "ConfirmPurchase";
    public static final String CUSTOMER_ID = "customerId";
    public static final String COMMAND_ID = "commandId";
    public static final String INVALID_TRANSITION = "INVALID_TRANSITION";
    public static final String UNKNOWN_MESSAGE_TYPE = "UNKNOWN_MESSAGE_TYPE";
    public static final String METRIC = "metric";
    public static final String EXPIRATION_LAG_SECONDS = "expiration_lag_seconds";
    public static final String VALUE = "value";
    public static final String EXPIRY_LAG_MESSAGE = "Reservation expiry lag";
    public static final String OPERATION = "operation";
    public static final String RESERVATION_ID = "reservationId";
    public static final String MESSAGE_ID_LOG = "message_id";
    public static final String OUTCOME = "outcome";
    public static final String EXPIRED_OUTCOME = "expired";
    public static final String NOOP_OUTCOME = "noop";
    public static final String EXPIRY_PROCESSED_MESSAGE = "Expiration command processed";
    public static final String LOCAL_PROFILE = "local";
    public static final String RESERVATION_NOT_DUE = "RESERVATION_NOT_DUE";
    public static final String ERROR_CLASS = "error_class";
    public static final String OUTBOX_RETRY_MESSAGE = "Outbox retry scheduled";
    public static final String RECOVERY_RETRY_MESSAGE = "Expiration recovery retry scheduled";
    public static final String RECEIVE_FAILED_MESSAGE = "SQS receive failed";
    public static final String MESSAGE_RETAINED_MESSAGE = "Message retained for retry";
    public static final String EARLY_TIMER_RETRY_MESSAGE = "Early timer retains original visibility";

    private SqsListenerConstants() {
    }
}
