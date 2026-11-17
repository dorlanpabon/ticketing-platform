package com.ticketing.orders.sqs.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.sqs.listener.mapper.ReservationPayloadMapperImpl;
import com.ticketing.orders.usecase.OrdersUseCase;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import software.amazon.awssdk.services.sqs.model.Message;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class SqsEventHandlerTest {
    @Test
    void acceptsFullInventoryWireSnapshotWithNullConfirmationId() {
        var useCase = mock(OrdersUseCase.class);
        var processor = new SqsEventHandler(useCase, new ReservationPayloadMapperImpl());
        var state = new Reservation("order", "event", "customer", "RESERVED", "NONE", 1, List.of("1"),
            List.of("1"), Instant.parse("2026-11-16T12:10:00Z"));
        when(useCase.receive(any(Envelope.class), eq(state))).thenReturn(Mono.empty());
        var message = Message.builder().body("""
            {"messageId":"inventory-message","type":"ReservationStateChanged","schemaVersion":1,
             "aggregateId":"order","aggregateVersion":1,"correlationId":"order","causationId":"order",
             "occurredAt":"2026-11-16T12:00:00Z","payload":{
               "orderId":"order","eventId":"event","customerId":"customer","status":"RESERVED",
               "resolution":"NONE","inventoryVersion":1,"requestedTicketIds":["1"],"activeTicketIds":["1"],
               "requestedAt":"2026-11-16T12:00:00Z","expiresAt":"2026-11-16T12:10:00Z",
               "type":"PURCHASE","actorId":"customer","reason":"","confirmationId":null}}
            """).build();
        StepVerifier.create(processor.apply(message)).verifyComplete();
        verify(useCase).receive(argThat(envelope -> envelope.payload().containsKey("confirmationId")
            && envelope.payload().get("confirmationId") == null), eq(state));
    }

    @Test
    void mapsVersionedInventorySnapshotAndPropagatesFailures() {
        var useCase = mock(OrdersUseCase.class);
        var processor = new SqsEventHandler(useCase, new ReservationPayloadMapperImpl());
        var mapper = JsonMapper.builder().build();
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var state = new Reservation("order", "event", "customer", "SOLD", "NONE", 3, List.of("1"),
            List.of("1"), now.plusSeconds(600));
        Map<String, Object> payload = mapper.convertValue(state, Map.class);
        var envelope = Envelope.create("ReservationStateChanged", "order", payload, now);
        when(useCase.receive(any(Envelope.class), eq(state))).thenReturn(Mono.empty());
        var message = Message.builder().body(mapper.writeValueAsString(envelope)).build();
        StepVerifier.create(processor.apply(message)).verifyComplete();
        when(useCase.receive(any(Envelope.class), eq(state)))
            .thenReturn(Mono.error(new IllegalStateException("unavailable")));
        StepVerifier.create(processor.apply(message)).expectError(IllegalStateException.class).verify();
        StepVerifier.create(processor.apply(Message.builder().body("{").build())).expectError().verify();
    }

    @Test
    void reconcilerDefersEachInvocationUntilSubscription() {
        var useCase = mock(OrdersUseCase.class);
        when(useCase.reconcile()).thenReturn(Mono.empty());
        var task = new Reconciler(useCase).reconcile();
        verifyNoInteractions(useCase);
        StepVerifier.create(task).verifyComplete();
        StepVerifier.create(task).verifyComplete();
        verify(useCase, times(2)).reconcile();
    }

    @Test
    void rejectsNullOrMissingTicketListsDuringJacksonConversionBeforeTheUseCase() {
        var useCase = mock(OrdersUseCase.class);
        var processor = new SqsEventHandler(useCase, new ReservationPayloadMapperImpl());
        var json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        for (String payload : List.of("{\"requestedTicketIds\":null,\"activeTicketIds\":[]}",
            "{\"requestedTicketIds\":[],\"activeTicketIds\":null}", "{\"activeTicketIds\":[]}",
            "{\"requestedTicketIds\":[]}")) {
            String body = """
                {"messageId":"inventory-message","type":"ReservationStateChanged","schemaVersion":1,
                 "aggregateId":"order","correlationId":"order","causationId":"order",
                 "occurredAt":"2026-11-16T12:00:00Z","payload":
                """ + payload + "}";
            Envelope originalEnvelope = json.readValue(body, Envelope.class);
            RuntimeException previousFailure = assertThrows(RuntimeException.class,
                () -> json.convertValue(originalEnvelope.payload(), Reservation.class));
            StepVerifier.create(processor.apply(Message.builder().body(body).build()))
                .expectErrorSatisfies(error -> assertEquals(previousFailure.getClass(), error.getClass()))
                .verify();
        }
        verifyNoInteractions(useCase);
    }
}
