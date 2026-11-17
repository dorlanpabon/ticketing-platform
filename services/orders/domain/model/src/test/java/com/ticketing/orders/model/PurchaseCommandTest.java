package com.ticketing.orders.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class PurchaseCommandTest {
    private static final Instant REQUESTED_AT = Instant.EPOCH;
    private static final Instant EXPIRES_AT = REQUESTED_AT.plusSeconds(600);

    @Test
    void purchaseMessageContainsTheOrderIdentityAndReservationWindow() {
        Envelope message = PurchaseCommand.from(order("PURCHASE", "buyer-1", ""));

        assertEquals("PurchaseRequested", message.type());
        assertEquals("order-1", message.aggregateId());
        assertEquals(REQUESTED_AT, message.occurredAt());
        assertEquals(Map.of(
            "orderId", "order-1",
            "eventId", "event-1",
            "customerId", "buyer-1",
            "ticketIds", List.of("1", "2"),
            "requestedAt", REQUESTED_AT.toString(),
            "expiresAt", EXPIRES_AT.toString(),
            "type", "PURCHASE",
            "actorId", "buyer-1",
            "reason", ""), message.payload());
    }

    @Test
    void complimentaryMessageKeepsTheRecipientSeparateFromTheActor() {
        Envelope message = PurchaseCommand.from(order("COMPLIMENTARY", "staff-1", "Venue guest"));

        assertEquals("ComplimentaryRequested", message.type());
        assertEquals("COMPLIMENTARY", message.payload().get("type"));
        assertEquals("buyer-1", message.payload().get("customerId"));
        assertEquals("staff-1", message.payload().get("actorId"));
        assertEquals("Venue guest", message.payload().get("reason"));
    }

    private static Order order(String type, String actorId, String reason) {
        return new Order("order-1", "event-1", "buyer-1", List.of("1", "2"), List.of(), "AVAILABLE", "RESERVING",
            "", 0, REQUESTED_AT, EXPIRES_AT, type, actorId, reason);
    }
}
