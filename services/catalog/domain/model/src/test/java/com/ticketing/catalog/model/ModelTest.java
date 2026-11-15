package com.ticketing.catalog.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelTest {
    @Test void unavailableDigestProviderFailsExplicitlyInsteadOfReturningAnUnsafeKey() {
        var missing = new java.security.NoSuchAlgorithmException("provider unavailable");
        try (var digests = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
            digests.when(() -> java.security.MessageDigest.getInstance("SHA-256")).thenThrow(missing);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Validation.hash("request"));
            assertEquals("SHA-256 unavailable", failure.getMessage());
            assertSame(missing, failure.getCause());
        }
    }

    @Test void validationRejectsMissingBlankOversizedAndUnsafeInput() {
        for (String text : Arrays.asList(null, " ", "abcd")) assertThrows(DomainFailure.class, () -> Validation.text(text, 3));
        assertEquals("ok", Validation.text(" ok ", 4));
        assertEquals("abc", Validation.text("abc", 3));
        for (String key : Arrays.asList(null, "short", "invalid key")) assertThrows(DomainFailure.class, () -> Validation.key(key));
        assertEquals("valid_key-01", Validation.key("valid_key-01"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Validation.hash("abc"));
        DomainFailure failure = new DomainFailure(409, "CONFLICT");
        assertEquals(409, failure.status()); assertEquals("CONFLICT", failure.code());
    }
    @Test void recordsCopyCollectionsAndPreserveEventIdentity() {
        Event event = new Event("e", "Concert", "Arena", Instant.EPOCH, 2, "PROVISIONING");
        assertEquals(new Event("e", "Concert", "Arena", Instant.EPOCH, 2, "ACTIVE"), event.activate());
        ArrayList<Event> items = new ArrayList<>(List.of(event));
        EventPage page = new EventPage(items, "c"); items.clear();
        assertEquals(1, page.items().size()); assertEquals("c", page.cursor());
        Map<String, Object> values = new HashMap<>(Map.of("eventId", "e"));
        Envelope envelope = Envelope.create("InventoryReady", "e", values, Instant.EPOCH); values.clear();
        assertEquals("e", envelope.payload().get("eventId"));
        assertEquals(1, envelope.schemaVersion()); assertEquals("e", envelope.aggregateId());
        assertEquals("e", envelope.correlationId()); assertEquals("e", envelope.causationId());
        OutboxEntry entry = new OutboxEntry("key", "owner", envelope);
        assertEquals("key", entry.key()); assertEquals("owner", entry.leaseOwner()); assertSame(envelope, entry.envelope());
    }
}
