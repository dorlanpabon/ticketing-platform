package com.ticketing.orders.sqs.listener.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.sqs.listener.dto.ReservationStatePayload;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class ReservationPayloadMapperTest {
    private final ReservationPayloadMapper mapper = new ReservationPayloadMapperImpl();

    @Test
    void mapsTheCompleteSnapshotWithoutChangingItsVersionOrDeadline() {
        Instant expiresAt = Instant.parse("2026-11-16T12:10:00Z");
        var payload = new ReservationStatePayload("order", "event", "owner", "AVAILABLE",
            "RESERVATION_EXPIRED", 4, List.of("1", "2"), List.of(), expiresAt);
        var expected = new Reservation("order", "event", "owner", "AVAILABLE", "RESERVATION_EXPIRED", 4,
            List.of("1", "2"), List.of(), expiresAt);
        assertEquals(expected, mapper.toDomain(payload));
    }

    @Test
    void preservesNullSourceWithoutSynthesizingAReservation() {
        assertNull(mapper.toDomain(null));
    }
}
