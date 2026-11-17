package com.ticketing.orders.model;

import com.ticketing.orders.model.utils.OrderProtocol;

import java.util.Map;

public final class PurchaseCommand {
    private PurchaseCommand() {
    }

    public static Envelope from(Order order) {
        String messageType = OrderProtocol.COMPLIMENTARY_TYPE.equals(order.type())
            ? OrderProtocol.COMPLIMENTARY_REQUESTED
            : OrderProtocol.PURCHASE_REQUESTED;
        return Envelope.create(messageType, order.orderId(), payload(order), order.requestedAt());
    }

    public static Map<String, Object> payload(Order order) {
        return Map.of(OrderProtocol.ORDER_ID, order.orderId(), OrderProtocol.EVENT_ID, order.eventId(),
            OrderProtocol.CUSTOMER_ID, order.customerId(), OrderProtocol.TICKET_IDS,
            order.requestedTicketIds(), OrderProtocol.REQUESTED_AT, order.requestedAt().toString(),
            OrderProtocol.EXPIRES_AT, order.expiresAt().toString(), OrderProtocol.TYPE, order.type(),
            OrderProtocol.ACTOR_ID, order.actorId(), OrderProtocol.REASON, order.reason());
    }
}
