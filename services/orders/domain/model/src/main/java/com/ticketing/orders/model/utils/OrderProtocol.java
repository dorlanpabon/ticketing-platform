package com.ticketing.orders.model.utils;

public final class OrderProtocol {
    public static final int SCHEMA_VERSION = 1;
    public static final long RESERVATION_SECONDS = 600;
    public static final String PURCHASE_TYPE = "PURCHASE";
    public static final String COMPLIMENTARY_TYPE = "COMPLIMENTARY";
    public static final String PURCHASE_REQUESTED = "PurchaseRequested";
    public static final String COMPLIMENTARY_REQUESTED = "ComplimentaryRequested";
    public static final String CONFIRM_PURCHASE = "ConfirmPurchase";
    public static final String RESERVATION_STATE_CHANGED = "ReservationStateChanged";
    public static final String EXPIRED_RESOLUTION = "RESERVATION_EXPIRED";
    public static final String NO_RESOLUTION = "";
    public static final String NO_REASON = "";
    public static final String FIELD_SEPARATOR = "\n";
    public static final String TICKET_SEPARATOR = ",";
    public static final String CONFIRM_KEY_SEPARATOR = "\nconfirm\n";
    public static final String CONFIRM_PREFIX = "confirm-";
    public static final String RECONCILIATION_PREFIX = "reconcile-";
    public static final String VERSION_SEPARATOR = "-";
    public static final String ORDER_ID = "orderId";
    public static final String EVENT_ID = "eventId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String TICKET_IDS = "ticketIds";
    public static final String REQUESTED_AT = "requestedAt";
    public static final String EXPIRES_AT = "expiresAt";
    public static final String TYPE = "type";
    public static final String ACTOR_ID = "actorId";
    public static final String REASON = "reason";
    public static final String COMMAND_ID = "commandId";

    private OrderProtocol() {
    }
}
