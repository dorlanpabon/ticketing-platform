package com.ticketing.orders.model;

import com.ticketing.orders.model.utils.ErrorCodes;
import com.ticketing.orders.model.utils.OrderProtocol;
import com.ticketing.orders.model.utils.OrderStates;

import java.time.Instant;
import java.util.List;

public record Order(String orderId, String eventId, String customerId, List<String> requestedTicketIds,
    List<String> activeTicketIds, String status, String workflowStatus, String resolution,
    long inventoryVersion, Instant requestedAt, Instant expiresAt, String type, String actorId,
    String reason) {
    public static final String RESERVING = OrderStates.RESERVING;
    public static final String AWAITING_CONFIRMATION = OrderStates.AWAITING_CONFIRMATION;
    public static final String CONFIRMING = OrderStates.CONFIRMING;
    public static final String COMPLETED = OrderStates.COMPLETED;
    public static final String EXPIRED = OrderStates.EXPIRED;
    public static final String REJECTED = OrderStates.REJECTED;

    public Order {
        requestedTicketIds = List.copyOf(requestedTicketIds);
        activeTicketIds = List.copyOf(activeTicketIds);
    }

    public Order withInventory(Reservation state) {
        if (!matchesInventoryIdentity(state))
            throw new DomainFailure(409, ErrorCodes.INVENTORY_IDENTITY_MISMATCH);
        if (state.inventoryVersion() <= inventoryVersion)
            return this;
        if (isFinal() && !status.equals(state.status()))
            throw new DomainFailure(409, ErrorCodes.INVALID_INVENTORY_TRANSITION);
        return new Order(orderId, eventId, customerId, requestedTicketIds, state.activeTicketIds(),
            state.status(), workflowFor(state), state.resolution(), state.inventoryVersion(), requestedAt,
            expiresAt, type, actorId, reason);
    }

    private boolean matchesInventoryIdentity(Reservation state) {
        return orderId.equals(state.orderId()) && eventId.equals(state.eventId())
            && customerId.equals(state.customerId()) && requestedTicketIds.equals(state.requestedTicketIds())
            && expiresAt.equals(state.expiresAt());
    }

    private boolean isFinal() {
        return status.equals(Reservation.SOLD) || status.equals(Reservation.COMPLIMENTARY);
    }

    private static String workflowFor(Reservation state) {
        return switch (state.status()) {
        case Reservation.RESERVED -> AWAITING_CONFIRMATION;
        case Reservation.PENDING_CONFIRMATION -> CONFIRMING;
        case Reservation.SOLD, Reservation.COMPLIMENTARY -> COMPLETED;
        case Reservation.AVAILABLE ->
            OrderProtocol.EXPIRED_RESOLUTION.equals(state.resolution()) || EXPIRED.equals(state.resolution())
                ? EXPIRED
                : REJECTED;
        default -> throw new DomainFailure(409, ErrorCodes.INVALID_INVENTORY_STATE);
        };
    }
}
