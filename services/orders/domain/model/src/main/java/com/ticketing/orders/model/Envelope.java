package com.ticketing.orders.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
public record Envelope(String messageId, String type, int schemaVersion, String aggregateId,
                       String correlationId, String causationId, Instant occurredAt, Map<String,Object> payload) {
    public Envelope {
        payload=Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        payload.keySet().forEach(Objects::requireNonNull);
    }
    public static Envelope create(String type, String id, Map<String,Object> payload, Instant now) {
        return new Envelope(UUID.randomUUID().toString(),type,1,id,id,id,now,payload);
    }
}
