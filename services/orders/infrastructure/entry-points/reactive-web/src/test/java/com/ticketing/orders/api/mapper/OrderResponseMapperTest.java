package com.ticketing.orders.api.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ticketing.orders.model.Order;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

class OrderResponseMapperTest {
    private final OrderResponseMapper mapper = new OrderResponseMapperImpl();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void preservesEverySerializedOrderFieldIncludingNullableValues() {
        Instant requestedAt = Instant.parse("2026-11-16T12:00:00Z");
        var order = new Order("order", "event", "owner", List.of("1", "2"), List.of("2"), "SOLD", "COMPLETED",
            "NONE", 7, requestedAt, requestedAt.plusSeconds(600), "PURCHASE", "actor", "reason");
        var response = mapper.toResponse(order);
        assertEquals(json.valueToTree(order), json.valueToTree(response));
        assertEquals(14, json.valueToTree(response).size());

        var nullable = new Order("order", "event", "owner", List.of(), List.of(), "AVAILABLE", "RESERVING",
            null, 0, requestedAt, requestedAt.plusSeconds(600), "PURCHASE", null, null);
        assertEquals(json.valueToTree(nullable), json.valueToTree(mapper.toResponse(nullable)));
    }

    @Test
    void preservesNullSourceWithoutSynthesizingAResponse() {
        assertNull(mapper.toResponse(null));
    }
}
