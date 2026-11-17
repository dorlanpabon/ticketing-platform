package com.ticketing.inventory.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Reservation(String orderId, String eventId, String customerId, TicketStatus status,
    String resolution, long inventoryVersion, List<String> requestedTicketIds, List<String> activeTicketIds,
    Instant requestedAt, Instant expiresAt, String type, String actorId, String reason,
    String confirmationId) {
    public Reservation {
        requestedTicketIds = List.copyOf(requestedTicketIds);
        activeTicketIds = List.copyOf(activeTicketIds);
    }

    public static Reservation accepted(ReservationIntent request) {
        return new Reservation(request.orderId(), request.eventId(), request.customerId(),
            request.complimentary() ? TicketStatus.COMPLIMENTARY : TicketStatus.RESERVED, "NONE", 1,
            request.ticketIds(), request.ticketIds(), request.requestedAt(), request.expiresAt(),
            request.type(), request.actorId(), request.reason(), null);
    }

    public static Reservation rejected(ReservationIntent request, String reason) {
        return new Reservation(request.orderId(), request.eventId(), request.customerId(),
            TicketStatus.AVAILABLE, reason, 1, request.ticketIds(), List.of(), request.requestedAt(),
            request.expiresAt(), request.type(), request.actorId(), request.reason(), null);
    }

    public boolean matches(ReservationIntent request) {
        return eventId.equals(request.eventId()) && customerId.equals(request.customerId())
            && requestedTicketIds.equals(request.ticketIds()) && requestedAt.equals(request.requestedAt())
            && expiresAt.equals(request.expiresAt()) && type.equals(request.type())
            && Objects.equals(actorId, request.actorId()) && Objects.equals(reason, request.reason());
    }

    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Reservation transition(TicketStatus target, String nextResolution, String commandId) {
        boolean allowed = status == TicketStatus.RESERVED
            && (target == TicketStatus.PENDING_CONFIRMATION || target == TicketStatus.AVAILABLE)
            || status == TicketStatus.PENDING_CONFIRMATION
                && (target == TicketStatus.SOLD || target == TicketStatus.AVAILABLE);
        if (!allowed)
            throw new InventoryException("INVALID_TRANSITION", 409);
        return new Reservation(orderId, eventId, customerId, target, nextResolution, inventoryVersion + 1,
            requestedTicketIds, target == TicketStatus.AVAILABLE ? List.of() : activeTicketIds, requestedAt,
            expiresAt, type, actorId, reason, commandId == null ? confirmationId : commandId);
    }
}
