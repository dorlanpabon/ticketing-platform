package com.ticketing.inventory.usecase.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.ReservationIntent;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class ReservationValidatorTest {
    private final Instant now = Instant.parse("2026-11-16T12:00:00Z");
    private final ReservationValidator validator = new ReservationValidator(Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void preservesErrorPrecedenceWhenSeveralReservationFieldsAreInvalid() {
        assertCode("INVALID_IDENTIFIER",
            new ReservationIntent("", "event", "customer", List.of(), null, null, "UNKNOWN", null, null));
        assertCode("INVALID_TICKETS", new ReservationIntent("order", "event", "customer", List.of(), null,
            null, "UNKNOWN", null, null));
        assertCode("INVALID_DEADLINE", new ReservationIntent("order", "event", "customer", List.of("1"), null,
            null, "UNKNOWN", null, null));
        assertCode("INVALID_PURCHASE_TYPE", new ReservationIntent("order", "event", "customer", List.of("1"),
            now, now.plusSeconds(600), "UNKNOWN", null, null));
        assertCode("INVALID_COMPLIMENTARY_REASON", new ReservationIntent("order", "event", "customer",
            List.of("1"), now, now.plusSeconds(600), "COMPLIMENTARY", null, null));
    }

    @Test
    void acceptsExactDeadlineAndClockToleranceButRejectsOneNanosecondBeyondEither() {
        assertDoesNotThrow(() -> validator.validate(intent(now, now.plusSeconds(600))));
        assertCode("INVALID_DEADLINE", intent(now, now.plusSeconds(600).plusNanos(1)));
        assertDoesNotThrow(() -> validator.validate(intent(now.plusSeconds(2), now.plusSeconds(602))));
        assertCode("INVALID_DEADLINE", intent(now.plusSeconds(2).plusNanos(1), now.plusSeconds(602)));
    }

    private ReservationIntent intent(Instant requestedAt, Instant expiresAt) {
        return new ReservationIntent("order", "event", "customer", List.of("1"), requestedAt, expiresAt,
            "PURCHASE", null, null);
    }

    private void assertCode(String code, ReservationIntent request) {
        assertEquals(code, assertThrows(InventoryException.class, () -> validator.validate(request)).code());
    }
}
