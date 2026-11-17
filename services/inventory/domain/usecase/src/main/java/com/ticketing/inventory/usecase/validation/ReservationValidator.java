package com.ticketing.inventory.usecase.validation;

import com.ticketing.inventory.model.InventoryErrorCodes;
import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.InventoryRules;
import com.ticketing.inventory.model.ReservationIntent;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;

/**
 * Validates reservation requests and their deadlines against inventory rules.
 */
public final class ReservationValidator {
    private final Clock clock;

    public ReservationValidator(Clock clock) {
        this.clock = clock;
    }

    public void validate(ReservationIntent request) {
        requireIdentifier(request.orderId());
        requireIdentifier(request.eventId());
        requireIdentifier(request.customerId());
        validateTickets(request);
        validateDeadline(request);
        validatePurchaseType(request);
    }

    public void requireIdentifier(String value) {
        if (value == null || !value.matches(InventoryRules.IDENTIFIER_PATTERN)) {
            throw new InventoryException(InventoryErrorCodes.INVALID_IDENTIFIER, 400);
        }
    }

    private static void validateTickets(ReservationIntent request) {
        if (request.ticketIds().isEmpty()
            || request.ticketIds().size() > InventoryRules.MAX_TICKETS_PER_RESERVATION
            || new HashSet<>(request.ticketIds()).size() != request.ticketIds().size()
            || request.ticketIds().stream().anyMatch(id -> !id.matches(InventoryRules.TICKET_NUMBER_PATTERN)
                || Integer.parseInt(id) > InventoryRules.MAX_EVENT_CAPACITY)) {
            throw new InventoryException(InventoryErrorCodes.INVALID_TICKETS, 400);
        }
    }

    private void validateDeadline(ReservationIntent request) {
        if (request.requestedAt() == null || request.expiresAt() == null
            || !request.expiresAt().isAfter(request.requestedAt())
            || Duration.between(request.requestedAt(), request.expiresAt())
                .compareTo(InventoryRules.MAX_RESERVATION_DURATION) > 0
            || request.requestedAt().isAfter(clock.instant().plus(InventoryRules.MAX_REQUEST_TIME_SKEW))) {
            throw new InventoryException(InventoryErrorCodes.INVALID_DEADLINE, 400);
        }
    }

    private static void validatePurchaseType(ReservationIntent request) {
        if (!InventoryRules.PURCHASE_TYPE.equals(request.type()) && !request.complimentary()) {
            throw new InventoryException(InventoryErrorCodes.INVALID_PURCHASE_TYPE, 400);
        }
        if (request.complimentary()) {
            validateComplimentary(request);
        }
    }

    private static void validateComplimentary(ReservationIntent request) {
        if (request.actorId() == null || request.actorId().isBlank() || request.reason() == null
            || request.reason().isBlank()
            || request.reason().length() > InventoryRules.MAX_COMPLIMENTARY_REASON_LENGTH) {
            throw new InventoryException(InventoryErrorCodes.INVALID_COMPLIMENTARY_REASON, 400);
        }
    }
}
