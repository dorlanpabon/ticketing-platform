package com.ticketing.inventory.model;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReservationTest {
    private final Instant now = Instant.parse("2026-11-16T12:00:00Z");
    private ReservationRequest request(String type) {
        return new ReservationRequest("o1", "e1", "c1", List.of("1", "2"), now, now.plusSeconds(600), type, "admin", "Guest");
    }

    @Test void reservesAndConfirmsWithoutChangingDeadline() {
        var reserved = Reservation.accepted(request("PURCHASE"));
        assertTrue(reserved.matches(request("PURCHASE")));
        assertTrue(reserved.status().isHeld());
        assertFalse(reserved.expiredAt(now.plusSeconds(599)));
        assertTrue(reserved.expiredAt(now.plusSeconds(600)));
        var pending = reserved.transition(TicketStatus.PENDING_CONFIRMATION, "NONE", "confirm:o1");
        var sold = pending.transition(TicketStatus.SOLD, "NONE", "confirm:o1");
        assertEquals(3, sold.inventoryVersion());
        assertEquals(reserved.expiresAt(), sold.expiresAt());
        assertEquals(List.of("1", "2"), sold.activeTicketIds());
        assertTrue(sold.status().isFinal());
        assertThrows(InventoryException.class, () -> sold.transition(TicketStatus.AVAILABLE, "NONE", null));
    }

    @Test void expirationReleasesEveryTicketButPreservesHistory() {
        var reserved = Reservation.accepted(request("PURCHASE"));
        var expired = reserved.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null);
        assertTrue(expired.activeTicketIds().isEmpty());
        assertEquals(reserved.requestedTicketIds(), expired.requestedTicketIds());
        assertFalse(expired.status().isHeld());
        assertFalse(expired.status().isFinal());
        assertThrows(InventoryException.class, () -> expired.transition(TicketStatus.RESERVED, "NONE", null));
        var pending = reserved.transition(TicketStatus.PENDING_CONFIRMATION, "NONE", "command");
        assertEquals("command", pending.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null).confirmationId());
    }

    @Test void complimentaryIsFinalAndRejectionsNeverOwnTickets() {
        var complimentary = Reservation.accepted(request("COMPLIMENTARY"));
        assertEquals(TicketStatus.COMPLIMENTARY, complimentary.status());
        assertTrue(complimentary.status().isFinal());
        assertFalse(complimentary.status().isHeld());
        assertThrows(InventoryException.class, () -> complimentary.transition(TicketStatus.SOLD, "NONE", null));
        var rejected = Reservation.rejected(request("PURCHASE"), "TICKETS_UNAVAILABLE");
        assertTrue(rejected.activeTicketIds().isEmpty());
        assertEquals("TICKETS_UNAVAILABLE", rejected.resolution());
    }

    @Test void allRequestFieldsParticipateInIdempotency() {
        var original = request("PURCHASE");
        var reservation = Reservation.accepted(original);
        var variations = List.of(
                new ReservationRequest("o1", "e2", "c1", original.ticketIds(), now, original.expiresAt(), "PURCHASE", "admin", "Guest"),
                new ReservationRequest("o1", "e1", "c2", original.ticketIds(), now, original.expiresAt(), "PURCHASE", "admin", "Guest"),
                new ReservationRequest("o1", "e1", "c1", List.of("3"), now, original.expiresAt(), "PURCHASE", "admin", "Guest"),
                new ReservationRequest("o1", "e1", "c1", original.ticketIds(), now.minusSeconds(1), original.expiresAt(), "PURCHASE", "admin", "Guest"),
                new ReservationRequest("o1", "e1", "c1", original.ticketIds(), now, now.plusSeconds(30), "PURCHASE", "admin", "Guest"),
                request("COMPLIMENTARY"),
                new ReservationRequest("o1", "e1", "c1", original.ticketIds(), now, original.expiresAt(), "PURCHASE", "other", "Guest"),
                new ReservationRequest("o1", "e1", "c1", original.ticketIds(), now, original.expiresAt(), "PURCHASE", "admin", "Other"));
        variations.forEach(value -> assertFalse(reservation.matches(value)));
        var defaults = new ReservationRequest("o", "e", "c", null, now, now.plusSeconds(1), null, null, null);
        assertTrue(defaults.ticketIds().isEmpty());
        assertEquals("PURCHASE", defaults.type());
    }

    @Test void countersMustConserveCapacityAndRemainNonnegative() {
        assertEquals(10, new Availability("e1", 10, 2, 2, 2, 2, 2).capacity());
        long[][] invalid = {{10, 1, 2, 2, 2, 2}, {1, -1, 2, 0, 0, 0}, {1, 2, -1, 0, 0, 0},
                {1, 2, 0, -1, 0, 0}, {1, 2, 0, 0, -1, 0}, {1, 2, 0, 0, 0, -1}};
        for (long[] count : invalid) {
            assertThrows(InventoryException.class, () -> new Availability("e", count[0], count[1], count[2], count[3], count[4], count[5]));
        }
        var error = new InventoryException("TEST", 409);
        assertEquals("TEST", error.code()); assertEquals(409, error.status());
        assertEquals(1, new TicketPage(List.of(new Ticket("e", "1", TicketStatus.AVAILABLE)), null).items().size());
    }
}
