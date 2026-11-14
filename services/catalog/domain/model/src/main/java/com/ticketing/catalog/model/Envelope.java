package com.ticketing.catalog.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
public record Envelope(String messageId, String type, int schemaVersion, String aggregateId,
                       String correlationId, String causationId, Instant occurredAt, Map<String,Object> payload) {
    public Envelope { payload=Map.copyOf(payload); }
    public static Envelope create(String type, String id, Map<String,Object> payload, Instant now) {
        return new Envelope(UUID.randomUUID().toString(),type,1,id,id,id,now,payload);
    }
}
