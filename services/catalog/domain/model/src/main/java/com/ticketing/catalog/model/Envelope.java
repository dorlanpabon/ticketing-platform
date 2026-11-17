package com.ticketing.catalog.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Envelope(String messageId, String type, int schemaVersion, String aggregateId,
    String correlationId, String causationId, Instant occurredAt, Map<String, Object> payload) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public Envelope {
        payload = Map.copyOf(payload);
    }

    public static Envelope create(String type, String id, Map<String, Object> payload, Instant now) {
        return new Envelope(UUID.randomUUID().toString(), type, CURRENT_SCHEMA_VERSION, id, id, id, now,
            payload);
    }
}
