package com.ticketing.orders.usecase;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.model.Validation;
import com.ticketing.orders.model.utils.ErrorCodes;
import com.ticketing.orders.model.utils.OrderProtocol;
import com.ticketing.orders.model.utils.ValidationRules;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Prepares orders, their idempotency keys and the inventory commands they require.
 */
final class OrderCreation {
    private OrderCreation() {
    }

    static Order prepare(String customer, String key, String eventId, List<String> tickets, String actor,
        String reason, boolean complimentary, Clock clock) {
        validate(customer, key, eventId, tickets, actor, reason, complimentary);
        var now = clock.instant();
        var sorted = tickets.stream().sorted().toList();
        String type = complimentary ? OrderProtocol.COMPLIMENTARY_TYPE : OrderProtocol.PURCHASE_TYPE;
        return new Order(UUID.randomUUID().toString(), eventId, customer, sorted, List.of(),
            Reservation.AVAILABLE, Order.RESERVING, OrderProtocol.NO_RESOLUTION, 0, now,
            now.plusSeconds(OrderProtocol.RESERVATION_SECONDS), type, actor,
            reason == null ? OrderProtocol.NO_REASON : reason);
    }

    private static void validate(String customer, String key, String eventId, List<String> tickets,
        String actor, String reason, boolean complimentary) {
        Validation.key(key);
        Validation.identifier(customer);
        Validation.identifier(eventId);
        Validation.identifier(actor);
        if (tickets == null || tickets.isEmpty() || tickets.size() > ValidationRules.MAX_TICKETS
            || new HashSet<>(tickets).size() != tickets.size()
            || tickets.stream()
                .anyMatch(ticket -> ticket == null || !ticket.matches(ValidationRules.TICKET_ID_PATTERN)
                    || Integer.parseInt(ticket) > ValidationRules.MAX_TICKET_ID)) {
            throw new DomainFailure(400, ErrorCodes.INVALID_TICKETS);
        }
        if (complimentary) {
            Validation.text(reason, ValidationRules.MAX_REASON_LENGTH);
        }
    }

    static String idempotencyKey(Order order, String key) {
        return Validation.hash(order.actorId() + OrderProtocol.FIELD_SEPARATOR + order.type()
            + OrderProtocol.FIELD_SEPARATOR + key);
    }

    static String fingerprint(Order order) {
        return Validation.hash(order.eventId() + OrderProtocol.FIELD_SEPARATOR
            + String.join(OrderProtocol.TICKET_SEPARATOR, order.requestedTicketIds())
            + OrderProtocol.FIELD_SEPARATOR + order.customerId() + OrderProtocol.FIELD_SEPARATOR
            + order.type() + OrderProtocol.FIELD_SEPARATOR + order.reason());
    }

}
