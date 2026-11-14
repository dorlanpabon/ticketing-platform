package com.ticketing.inventory.sqs.listener;

import com.ticketing.inventory.model.*;
import com.ticketing.inventory.usecase.InventoryUseCase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;

class SQSProcessorTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test void decodesProvisionReservationAndConfirmationCommands() {
        var inventory = mock(InventoryUseCase.class);
        var processor = new SQSProcessor(inventory);
        when(inventory.provision("e1", 10)).thenReturn(Mono.empty());
        StepVerifier.create(processor.apply(message("ProvisionInventory", Map.of("eventId", "e1", "capacity", 10)))).verifyComplete();
        StepVerifier.create(processor.apply(message("EventCreated", Map.of("eventId", "e1", "capacity", 10)))).verifyComplete();
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var request = new ReservationRequest("o1", "e1", "c1", List.of("1"), now, now.plusSeconds(600), "PURCHASE", null, null);
        when(inventory.reserve(request)).thenReturn(Mono.just(Reservation.accepted(request)));
        StepVerifier.create(processor.apply(message("PurchaseRequested", request))).verifyComplete();
        var courtesy = new ReservationRequest("o2", "e1", "guest", List.of("2"), now, now.plusSeconds(600), "COMPLIMENTARY", "admin", "Guest");
        when(inventory.reserve(courtesy)).thenReturn(Mono.just(Reservation.accepted(courtesy)));
        StepVerifier.create(processor.apply(message("ComplimentaryRequested", courtesy))).verifyComplete();
        when(inventory.expireReservation(eq("o1"), eq(request.expiresAt()), any())).thenReturn(Mono.just(Reservation.accepted(request)));
        StepVerifier.create(processor.apply(message("ExpireReservation", Map.of("orderId", "o1", "expiresAt", request.expiresAt().toString())))).verifyComplete();
        when(inventory.recoverExpired(eq(100), any())).thenReturn(Mono.just(0L));
        StepVerifier.create(processor.apply(message("SweepExpiredReservations", Map.of()))).verifyComplete();
        when(inventory.confirm("o1", "c1", "cmd")).thenReturn(Mono.just(Reservation.accepted(request)));
        StepVerifier.create(processor.apply(message("ConfirmPurchase", Map.of("orderId", "o1", "customerId", "c1", "commandId", "cmd")))).verifyComplete();
        for (String terminal : List.of("RESERVATION_EXPIRED", "INVALID_TRANSITION")) {
            when(inventory.confirm("o1", "c1", "cmd")).thenReturn(Mono.error(new InventoryException(terminal, 409)));
            StepVerifier.create(processor.apply(message("ConfirmPurchase", Map.of("orderId", "o1", "customerId", "c1", "commandId", "cmd")))).verifyComplete();
        }
        when(inventory.confirm("o1", "c1", "cmd")).thenReturn(Mono.error(new InventoryException("DEPENDENCY_UNAVAILABLE", 503)));
        StepVerifier.create(processor.apply(message("ConfirmPurchase", Map.of("orderId", "o1", "customerId", "c1", "commandId", "cmd")))).expectError(InventoryException.class).verify();
    }

    @Test void malformedAndUnsupportedMessagesAreNotAcknowledged() {
        var processor = new SQSProcessor(mock(InventoryUseCase.class));
        StepVerifier.create(processor.apply(Message.builder().body("{").build())).expectError().verify();
        StepVerifier.create(processor.apply(Message.builder().body("{\"schemaVersion\":2,\"messageId\":\"m\"}").build())).expectError(InventoryException.class).verify();
        StepVerifier.create(processor.apply(Message.builder().body("{\"schemaVersion\":1,\"messageId\":\"\"}").build())).expectError(InventoryException.class).verify();
        StepVerifier.create(processor.apply(message("Unknown", Map.of()))).expectError(InventoryException.class).verify();
    }

    private Message message(String type, Object payload) {
        return Message.builder().body(json.writeValueAsString(Map.of("messageId", "message", "schemaVersion", 1, "type", type, "payload", payload))).build();
    }
}
