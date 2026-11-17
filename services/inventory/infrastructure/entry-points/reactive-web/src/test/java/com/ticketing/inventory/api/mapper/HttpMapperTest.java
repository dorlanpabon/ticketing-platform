package com.ticketing.inventory.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketing.inventory.api.dto.request.ReserveTicketsRequest;
import com.ticketing.inventory.model.Availability;
import com.ticketing.inventory.model.Reservation;
import com.ticketing.inventory.model.ReservationIntent;
import com.ticketing.inventory.model.Ticket;
import com.ticketing.inventory.model.TicketPage;
import com.ticketing.inventory.model.TicketStatus;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

class HttpMapperTest {
    private final HttpMapper mapper = new HttpMapperImpl();
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void pathIdentityWinsAndCourtesyMetadataAndDeadlineArePreserved() {
        var requestedAt = Instant.parse("2026-11-16T12:00:00Z");
        var expiresAt = requestedAt.plusSeconds(600);
        var body = new ReserveTicketsRequest("body-order", "event", "guest", List.of("1", "17"), requestedAt,
            expiresAt, "COMPLIMENTARY", "admin", "Invited guest");

        var intent = mapper.toIntent(body, "path-order");

        assertEquals(new ReservationIntent("path-order", "event", "guest", List.of("1", "17"), requestedAt,
            expiresAt, "COMPLIMENTARY", "admin", "Invited guest"), intent);
        assertTrue(intent.complimentary());
    }

    @Test
    void missingOptionalFieldsRetainDomainDefaults() {
        var requestedAt = Instant.parse("2026-11-16T12:00:00Z");
        var body = new ReserveTicketsRequest(null, "event", "customer", null, requestedAt,
            requestedAt.plusSeconds(600), null, null, null);

        var intent = mapper.toIntent(body, "path-order");

        assertEquals("path-order", intent.orderId());
        assertEquals("PURCHASE", intent.type());
        assertTrue(intent.ticketIds().isEmpty());
        assertEquals(body.requestedAt(), intent.requestedAt());
        assertEquals(body.expiresAt(), intent.expiresAt());
    }

    @Test
    void reservationResponsesPreserveEverySerializedPropertyAndState() {
        var requestedAt = Instant.parse("2026-11-16T12:00:00Z");
        for (TicketStatus status : TicketStatus.values()) {
            var reservation = new Reservation("order", "event", "customer", status, "NONE", 3,
                List.of("1", "17"), List.of("1", "17"), requestedAt, requestedAt.plusSeconds(600),
                status == TicketStatus.COMPLIMENTARY ? "COMPLIMENTARY" : "PURCHASE",
                status == TicketStatus.COMPLIMENTARY ? "admin" : null,
                status == TicketStatus.COMPLIMENTARY ? "Guest" : null,
                status == TicketStatus.SOLD ? "confirmation" : null);

            var response = mapper.toReservationResponse(reservation);

            assertEquals(json.valueToTree(reservation), json.valueToTree(response));
            assertEquals(status.name(), response.status());
        }
    }

    @Test
    void availabilityAndTicketPagesPreserveCountersStatusesAndNullCursor() {
        var availability = new Availability("event", 10, 3, 2, 1, 2, 2);
        assertEquals(json.valueToTree(availability),
            json.valueToTree(mapper.toAvailabilityResponse(availability)));
        var page = new TicketPage(List.of(new Ticket("event", "1", TicketStatus.AVAILABLE),
            new Ticket("event", "2", TicketStatus.RESERVED),
            new Ticket("event", "3", TicketStatus.PENDING_CONFIRMATION),
            new Ticket("event", "4", TicketStatus.SOLD),
            new Ticket("event", "5", TicketStatus.COMPLIMENTARY)), "6");
        assertEquals(json.valueToTree(page), json.valueToTree(mapper.toTicketPageResponse(page)));
        var empty = new TicketPage(List.of(), null);
        assertEquals(json.valueToTree(empty), json.valueToTree(mapper.toTicketPageResponse(empty)));
    }
}
