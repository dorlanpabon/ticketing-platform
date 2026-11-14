package com.ticketing.orders.sqs.listener;

import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.usecase.OrdersUseCase;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;

class SQSProcessorTest {
    @Test void mapsVersionedInventorySnapshotAndPropagatesFailures() {
        var useCase = mock(OrdersUseCase.class);
        var processor = new SQSProcessor(useCase);
        var mapper = JsonMapper.builder().build();
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var state = new Reservation("order", "event", "customer", "SOLD", "NONE", 3,
                java.util.List.of("1"), java.util.List.of("1"), now.plusSeconds(600));
        Map<String,Object> payload = mapper.convertValue(state, Map.class);
        var envelope = Envelope.create("ReservationStateChanged", "order", payload, now);
        when(useCase.receive(any(Envelope.class), eq(state))).thenReturn(Mono.empty());
        var message = Message.builder().body(mapper.writeValueAsString(envelope)).build();
        StepVerifier.create(processor.apply(message)).verifyComplete();
        when(useCase.receive(any(Envelope.class), eq(state))).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        StepVerifier.create(processor.apply(message)).expectError(IllegalStateException.class).verify();
        StepVerifier.create(processor.apply(Message.builder().body("{").build())).expectError().verify();
    }

    @Test void reconcilerDefersEachInvocationUntilSubscription() {
        var useCase = mock(OrdersUseCase.class);
        when(useCase.reconcile()).thenReturn(Mono.empty());
        var task = new OrderReconciler(useCase).reconcile();
        verifyNoInteractions(useCase);
        StepVerifier.create(task).verifyComplete(); StepVerifier.create(task).verifyComplete();
        verify(useCase, times(2)).reconcile();
    }
}
