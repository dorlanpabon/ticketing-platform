package com.ticketing.orders.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class OrderTest {
    Order order() {
        return new Order("o", "e", "c", List.of("1"), List.of(), "AVAILABLE", "RESERVING", "", 0,
            Instant.EPOCH, Instant.EPOCH.plusSeconds(600), "PURCHASE", "c", "");
    }

    Reservation state(String status, String resolution, long version) {
        return new Reservation("o", "e", "c", status, resolution, version, List.of("1"), List.of("1"),
            Instant.EPOCH.plusSeconds(600));
    }

    @Test
    void projectsEveryStateAndIgnoresOlderVersions() {
        Map<String, String> states = Map.of("RESERVED", "AWAITING_CONFIRMATION", "PENDING_CONFIRMATION",
            "CONFIRMING", "SOLD", "COMPLETED", "COMPLIMENTARY", "COMPLETED", "AVAILABLE", "REJECTED");
        states.forEach((state, workflow) -> assertEquals(workflow,
            order().withInventory(state(state, "", 1)).workflowStatus()));
        assertEquals("EXPIRED", order().withInventory(state("AVAILABLE", "EXPIRED", 1)).workflowStatus());
        assertEquals("EXPIRED",
            order().withInventory(state("AVAILABLE", "RESERVATION_EXPIRED", 1)).workflowStatus());
    }

    @Test
    void staleAndMismatchedSnapshotsCannotChangeOwnership() {
        Order original = order();
        assertSame(original, original.withInventory(state("SOLD", "", 0)));
        assertThrows(DomainFailure.class, () -> original.withInventory(
            new Reservation("other", "e", "c", "SOLD", "", 1, List.of(), List.of(), Instant.EPOCH)));
        assertThrows(DomainFailure.class, () -> original.withInventory(
            new Reservation("o", "other", "c", "SOLD", "", 1, List.of(), List.of(), Instant.EPOCH)));
        assertThrows(DomainFailure.class, () -> original.withInventory(
            new Reservation("o", "e", "other", "SOLD", "", 1, List.of(), List.of(), Instant.EPOCH)));
        assertThrows(DomainFailure.class, () -> original.withInventory(state("UNKNOWN", "", 1)));
        assertThrows(DomainFailure.class, () -> original.withInventory(new Reservation("o", "e", "c", "SOLD",
            "", 1, List.of("2"), List.of("2"), Instant.EPOCH.plusSeconds(600))));
        assertThrows(DomainFailure.class, () -> original.withInventory(new Reservation("o", "e", "c", "SOLD",
            "", 1, List.of("1"), List.of("1"), Instant.EPOCH.plusSeconds(601))));
        Order sold = original.withInventory(state("SOLD", "", 3));
        assertThrows(DomainFailure.class, () -> sold.withInventory(state("RESERVED", "", 4)));
        assertEquals("SOLD", sold.withInventory(state("SOLD", "", 4)).status());
        Order courtesy = original.withInventory(state("COMPLIMENTARY", "", 3));
        assertThrows(DomainFailure.class, () -> courtesy.withInventory(state("AVAILABLE", "", 4)));
        assertThrows(UnsupportedOperationException.class, () -> original.requestedTicketIds().add("2"));
    }

    @Test
    void validationBoundsAndFingerprintAreDeterministic() {
        for (String value : Arrays.asList(null, "", " ", "x".repeat(129)))
            assertThrows(DomainFailure.class, () -> Validation.text(value, 128));
        assertEquals("hello", Validation.text(" hello ", 128));
        for (String value : Arrays.asList(null, "a", "bad key spaces"))
            assertThrows(DomainFailure.class, () -> Validation.key(value));
        assertEquals("valid-key", Validation.key("valid-key"));
        for (String value : Arrays.asList(null, "bad space", "x".repeat(129)))
            assertThrows(DomainFailure.class, () -> Validation.identifier(value));
        assertEquals("valid-id", Validation.identifier("valid-id"));
        assertEquals(64, Validation.hash("payload").length());
        assertNotEquals(Validation.hash("a"), Validation.hash("b"));
        DomainFailure failure = new DomainFailure(409, "CONFLICT");
        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
        Envelope message = Envelope.create("test", "o", Map.of("value", 1), Instant.EPOCH);
        assertEquals(1, message.schemaVersion());
        assertThrows(UnsupportedOperationException.class, () -> message.payload().put("bad", 2));
        OutboxEntry outbox = new OutboxEntry("key", "owner", message);
        assertEquals(message, outbox.envelope());
    }

    @Test
    void envelopePreservesNullOptionalsWithoutSharingTheInputMap() {
        var payload = new HashMap<String, Object>();
        payload.put("confirmationId", null);
        payload.put("type", "PURCHASE");
        var envelope = Envelope.create("ReservationStateChanged", "o", payload, Instant.EPOCH);
        payload.clear();
        assertTrue(envelope.payload().containsKey("confirmationId"));
        assertNull(envelope.payload().get("confirmationId"));
        assertEquals("PURCHASE", envelope.payload().get("type"));
        assertThrows(UnsupportedOperationException.class,
            () -> envelope.payload().put("confirmationId", "changed"));
    }

    @Test
    void envelopeStillRejectsNullPayloadAndNullKeys() {
        assertThrows(NullPointerException.class, () -> Envelope.create("test", "o", null, Instant.EPOCH));
        var payload = new HashMap<String, Object>();
        payload.put(null, "invalid-key");
        assertThrows(NullPointerException.class, () -> Envelope.create("test", "o", payload, Instant.EPOCH));
    }
}
