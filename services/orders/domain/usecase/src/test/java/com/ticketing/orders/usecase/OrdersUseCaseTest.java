package com.ticketing.orders.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.OrderSubmission;
import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.model.gateways.InventoryPort;
import com.ticketing.orders.model.gateways.OrderEnqueuePort;
import com.ticketing.orders.model.gateways.OrdersPort;
import com.ticketing.orders.model.utils.ErrorCodes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

class OrdersUseCaseTest {
    final OrdersPort port = mock(OrdersPort.class);
    final InventoryPort inventory = mock(InventoryPort.class);
    final OrderEnqueuePort enqueue = mock(OrderEnqueuePort.class);
    final Instant now = Instant.parse("2026-11-16T00:00:00Z");
    final OrdersUseCase useCase = new OrdersUseCase(port, inventory, Clock.fixed(now, ZoneOffset.UTC),
        enqueue);

    @BeforeEach
    void brokerAcknowledgesByDefault() {
        when(enqueue.enqueue(anyString())).thenReturn(Mono.empty());
    }

    Order order() {
        return new Order("order", "event", "owner", List.of("1"), List.of(), "AVAILABLE", "RESERVING", "", 0,
            now, now.plusSeconds(600), "PURCHASE", "owner", "");
    }

    Reservation state(String status, String resolution) {
        return new Reservation("order", "event", "owner", status, resolution, 1, List.of("1"),
            status.equals("AVAILABLE") ? List.of() : List.of("1"), now.plusSeconds(600));
    }

    @Test
    void durableAcceptancePreservesOriginalDeadlineAndOutboxWithoutCallingInventory() {
        when(port.create(any(), anyString(), anyString(), any())).thenAnswer(call -> {
            Order saved = call.getArgument(0);
            Envelope message = call.getArgument(3);
            assertEquals(saved.orderId(), message.aggregateId());
            assertEquals(saved.orderId(), message.payload().get("orderId"));
            assertEquals(saved.requestedAt().toString(), message.payload().get("requestedAt"));
            assertEquals(saved.expiresAt().toString(), message.payload().get("expiresAt"));
            assertEquals(now, message.occurredAt());
            return Mono.just(new OrderSubmission(saved, message.messageId()));
        });
        StepVerifier
            .create(useCase.create("owner", "request-0001", "event", List.of("2", "1"), "owner", "", false))
            .assertNext(saved -> {
                assertEquals("RESERVING", saved.workflowStatus());
                assertEquals(now, saved.requestedAt());
                assertEquals(now.plusSeconds(600), saved.expiresAt());
                assertEquals(List.of("1", "2"), saved.requestedTicketIds());
            }).verifyComplete();
        verify(port).create(any(), anyString(), anyString(),
            argThat(message -> message.type().equals("PurchaseRequested")
                && message.payload().get("ticketIds").equals(List.of("1", "2"))));
        verifyNoInteractions(inventory);
    }

    @Test
    void purchasePreservesIdempotencyHashesAndLexicographicTicketOrder() {
        when(port.create(any(), anyString(), anyString(), any()))
            .thenAnswer(call -> Mono.just(new OrderSubmission(call.getArgument(0), "stored-command")));
        StepVerifier.create(
            useCase.create("owner", "request-0001", "event", List.of("2", "10", "1"), "owner", null, false))
            .assertNext(saved -> {
                assertEquals(List.of("1", "10", "2"), saved.requestedTicketIds());
                assertEquals("", saved.reason());
            }).verifyComplete();
        verify(port).create(any(), eq("7c35c1c9854b2dfff2ebefe2f2537a1006a799ab7f26464cbf78d74c70a97f8e"),
            eq("6351d7aec6890d06d516021c6d15fc5c4eb3bcc34ad640f273151b9cd59d6698"),
            argThat(message -> message.type().equals("PurchaseRequested")
                && message.payload().get("ticketIds").equals(List.of("1", "10", "2"))));
        verifyNoInteractions(inventory);
    }

    @Test
    void complimentaryPreservesActorScopedKeyAndUntrimmedReasonFingerprint() {
        when(port.create(any(), anyString(), anyString(), any()))
            .thenAnswer(call -> Mono.just(new OrderSubmission(call.getArgument(0), "stored-command")));
        StepVerifier.create(
            useCase.create("guest", "request-0002", "event", List.of("3", "1"), "admin", "  speaker  ", true))
            .assertNext(saved -> {
                assertEquals("guest", saved.customerId());
                assertEquals("admin", saved.actorId());
                assertEquals("  speaker  ", saved.reason());
            }).verifyComplete();
        verify(port).create(any(), eq("4707c33c56bae48e10b7584f971b5f5050a15cdcd8de40b8a1b035606305f051"),
            eq("6dd9b72746dc81753f1371b664bb3b99943c1f187bfab149cc2951b1e547a49a"),
            argThat(message -> message.type().equals("ComplimentaryRequested")
                && message.payload().get("reason").equals("  speaker  ")));
        verifyNoInteractions(inventory);
    }

    @Test
    void creationValidationIsDeferredAndKeepsErrorPrecedenceBeforeReadingClock() {
        Clock unreadClock = mock(Clock.class);
        OrdersUseCase validating = new OrdersUseCase(port, inventory, unreadClock, enqueue);
        List<Mono<Order>> requests = List.of(validating.create(null, "bad", null, null, null, null, true),
            validating.create(null, "request-0001", null, null, null, null, true),
            validating.create("owner", "request-0001", null, null, null, null, true),
            validating.create("owner", "request-0001", "event", null, null, null, true),
            validating.create("owner", "request-0001", "event", null, "admin", null, true),
            validating.create("owner", "request-0001", "event", List.of("1"), "admin", null, true));
        List<String> expectedCodes = List.of("INVALID_IDEMPOTENCY_KEY", "INVALID_IDENTIFIER",
            "INVALID_IDENTIFIER", "INVALID_IDENTIFIER", "INVALID_TICKETS", "INVALID_INPUT");
        verifyNoInteractions(port, inventory, unreadClock);
        for (int index = 0; index < requests.size(); index++) {
            String expected = expectedCodes.get(index);
            StepVerifier.create(requests.get(index)).expectErrorSatisfies(error -> {
                DomainFailure failure = assertInstanceOf(DomainFailure.class, error);
                assertEquals(400, failure.status());
                assertEquals(expected, failure.code());
            }).verify();
        }
        verifyNoInteractions(port, inventory, unreadClock);
    }

    @Test
    void acceptanceWaitsForDurablePersistenceAndBrokerAcknowledgement() {
        Sinks.One<OrderSubmission> committed = Sinks.one();
        Sinks.Empty<Void> acknowledged = Sinks.empty();
        Order saved = order();
        when(port.create(any(), anyString(), anyString(), any())).thenReturn(committed.asMono());
        when(enqueue.enqueue("stored-command")).thenReturn(acknowledged.asMono());
        StepVerifier
            .withVirtualTime(
                () -> useCase.create("owner", "request-0001", "event", List.of("1"), "owner", "", false))
            .expectSubscription().expectNoEvent(Duration.ofSeconds(1)).then(() -> {
                verifyNoInteractions(enqueue);
                assertEquals(Sinks.EmitResult.OK,
                    committed.tryEmitValue(new OrderSubmission(saved, "stored-command")));
            }).expectNoEvent(Duration.ofSeconds(1))
            .then(() -> assertEquals(Sinks.EmitResult.OK, acknowledged.tryEmitEmpty())).expectNext(saved)
            .verifyComplete();
        verify(enqueue).enqueue("stored-command");
        verifyNoInteractions(inventory);
    }

    @Test
    void failedPersistenceCannotProduceAcceptance() {
        var failure = new DomainFailure(503, "PERSISTENCE_UNAVAILABLE");
        when(port.create(any(), anyString(), anyString(), any())).thenReturn(Mono.error(failure));
        StepVerifier
            .create(useCase.create("owner", "request-0001", "event", List.of("1"), "owner", "", false))
            .expectErrorMatches(error -> error == failure).verify();
        verifyNoInteractions(inventory, enqueue);
    }

    @Test
    void unconfirmedPurchaseFailsAndRetryWaitsOnTheSameStoredCommand() {
        Order saved = order();
        var failure = new DomainFailure(503, ErrorCodes.ORDER_ENQUEUE_UNAVAILABLE);
        when(port.create(any(), anyString(), anyString(), any()))
            .thenReturn(Mono.just(new OrderSubmission(saved, "stored-command")));
        when(enqueue.enqueue("stored-command")).thenReturn(Mono.error(failure), Mono.empty());
        var purchase = useCase.create("owner", "request-0001", "event", List.of("1"), "owner", "", false);
        StepVerifier.create(purchase).expectErrorMatches(error -> error == failure).verify();
        StepVerifier.create(purchase).expectNext(saved).verifyComplete();
        verify(enqueue, times(2)).enqueue("stored-command");
    }

    @Test
    void idempotentReplayReturnsOriginalTerminalSnapshotAndDeadline() {
        Order saved = new Order("order", "event", "owner", List.of("1"), List.of("1"), "SOLD", "COMPLETED",
            "NONE", 3, now.minusSeconds(45), now.plusSeconds(555), "PURCHASE", "owner", "");
        when(port.create(any(), anyString(), anyString(), any()))
            .thenReturn(Mono.just(new OrderSubmission(saved, "stored-command")));
        StepVerifier
            .create(useCase.create("owner", "request-0001", "event", List.of("1"), "owner", "", false))
            .expectNext(saved).verifyComplete();
        verifyNoInteractions(inventory);
    }

    @Test
    void invalidSelectionsNeverReachPersistence() {
        List<List<String>> invalid = Arrays.asList(null, List.of(), List.of("1", "1"), List.of("01"),
            List.of("0"), List.of("-1"), Arrays.asList("1", null),
            IntStream.rangeClosed(1, 11).mapToObj(Integer::toString).toList());
        for (var tickets : invalid)
            StepVerifier.create(useCase.create("owner", "request-0001", "event", tickets, "owner", "", false))
                .expectError(DomainFailure.class).verify();
        verifyNoInteractions(port, inventory);
    }

    @Test
    void complimentaryRequiresReasonAndCreatesDistinctCommand() {
        StepVerifier.create(useCase.create("owner", "request-0001", "event", List.of("1"), "admin", "", true))
            .expectError(DomainFailure.class).verify();
        when(port.create(any(), anyString(), anyString(), any()))
            .thenAnswer(call -> Mono.just(new OrderSubmission(call.getArgument(0), "stored-command")));
        StepVerifier
            .create(useCase.create("owner", "request-0002", "event", List.of("1"), "admin", "speaker", true))
            .expectNextCount(1).verifyComplete();
        verify(port).create(any(), anyString(), anyString(),
            argThat(message -> message.type().equals("ComplimentaryRequested")));
        verifyNoInteractions(inventory);
    }

    @Test
    void ownerIsCheckedBeforeInventoryAndDependencyErrorsPropagate() {
        when(port.find("order")).thenReturn(Mono.just(order()));
        StepVerifier.create(useCase.get("intruder", "order"))
            .expectErrorMatches(error -> ((DomainFailure) error).status() == 404).verify();
        verifyNoInteractions(inventory);
        when(inventory.find("order")).thenReturn(Mono.error(new DomainFailure(503, "INVENTORY_UNAVAILABLE")));
        StepVerifier.create(useCase.get("owner", "order"))
            .expectErrorMatches(error -> ((DomainFailure) error).status() == 503).verify();
    }

    @Test
    void pendingIntentCanBeObservedBeforeInventoryExists() {
        when(port.find("order")).thenReturn(Mono.just(order()));
        when(inventory.find("order")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.get("owner", "order")).expectNext(order()).verifyComplete();
        StepVerifier.create(useCase.confirm("owner", "order", "confirm-0001"))
            .expectErrorMatches(error -> ((DomainFailure) error).status() == 409).verify();
    }

    @Test
    void confirmUsesStableCommandIdAcrossHttpKeys() {
        when(port.find("order")).thenReturn(Mono.just(order()));
        when(inventory.find("order")).thenReturn(Mono.just(state("RESERVED", "")));
        when(port.confirm(any(), anyString(), any())).thenReturn(Mono.empty());
        for (String key : List.of("confirm-0001", "confirm-0002"))
            StepVerifier.create(useCase.confirm("owner", "order", key)).expectNextCount(1).verifyComplete();
        verify(port, times(2)).confirm(any(), anyString(),
            argThat(message -> message.messageId().equals("confirm-order")
                && message.payload().get("commandId").equals("confirm-order")));
        verify(port).confirm(any(), eq("9eaec93aa2161b7e6b2dea3de71e37ce85bd3de7cdeb1d1a519b5a17240e58c2"),
            any());
    }

    @Test
    void soldConfirmationIsIdempotentAndDeadlineEqualityExpires() {
        when(port.find("order")).thenReturn(Mono.just(order()));
        when(inventory.find("order")).thenReturn(Mono.just(state("SOLD", "")));
        when(port.confirm(any(), anyString(), any())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.confirm("owner", "order", "confirm-0001")).expectNextCount(1)
            .verifyComplete();
        verify(port).confirm(any(), anyString(), any());
        when(inventory.find("order")).thenReturn(Mono.just(state("RESERVED", "")));
        OrdersUseCase deadline = new OrdersUseCase(port, inventory,
            Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC), enqueue);
        StepVerifier.create(deadline.confirm("owner", "order", "confirm-0002"))
            .expectErrorMatches(error -> ((DomainFailure) error).status() == 410).verify();
    }

    @Test
    void rejectsUnsupportedEnvelopeAndReconcilesRecoverableOrders() {
        Envelope wrong = Envelope.create("Other", "order", Map.of(), now);
        StepVerifier.create(useCase.receive(wrong, state("RESERVED", ""))).expectError(DomainFailure.class)
            .verify();
        Envelope valid = Envelope.create("ReservationStateChanged", "order", Map.of(), now);
        when(port.project(any(), anyString())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.receive(valid, state("RESERVED", ""))).verifyComplete();
        when(port.due()).thenReturn(Flux.just(order()));
        when(inventory.find("order")).thenReturn(Mono.just(state("SOLD", "")));
        when(port.reviewed(any())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete();
        verify(port).reviewed(order());
    }

    @Test
    void absentInventoryStateIsRecoveredFromTheOriginalImmutableIntent() {
        when(port.due()).thenReturn(Flux.just(order()));
        when(inventory.find("order")).thenReturn(Mono.empty());
        when(inventory.reserve(order())).thenReturn(Mono.just(state("RESERVED", "NONE")));
        when(port.project(any(), anyString())).thenReturn(Mono.empty());
        when(port.reviewed(order())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete();
        verify(inventory).reserve(order());
    }

    @Test
    void failedRecoveryRetainsTheDueOrderAndCanSucceedOnTheNextCycle() {
        when(port.due()).thenReturn(Flux.just(order()));
        when(inventory.find("order"))
            .thenReturn(Mono.error(new IllegalStateException("temporarily unavailable")));
        when(port.reviewFailed(order())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete();
        verify(port).reviewFailed(order());
        verify(port, never()).reviewed(any());
        verify(inventory, never()).reserve(any());
        when(inventory.find("order")).thenReturn(Mono.just(state("SOLD", "NONE")));
        when(port.project(any(), anyString())).thenReturn(Mono.empty());
        when(port.reviewed(order())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete();
        verify(port).reviewed(order());
    }

    @Test
    void oneUnavailableReservationDoesNotStarveOtherDueOrders() {
        Order other = new Order("other", "event", "owner", List.of("2"), List.of(), "AVAILABLE", "RESERVING",
            "", 0, now, now.plusSeconds(600), "PURCHASE", "owner", "");
        Reservation recovered = new Reservation("other", "event", "owner", "RESERVED", "NONE", 1,
            List.of("2"), List.of("2"), now.plusSeconds(600));
        when(port.due()).thenReturn(Flux.just(order(), other));
        when(inventory.find("order")).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        when(port.reviewFailed(order())).thenReturn(Mono.empty());
        when(inventory.find("other")).thenReturn(Mono.just(recovered));
        when(port.project(eq(recovered), anyString())).thenReturn(Mono.empty());
        when(port.reviewed(other)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete();
        verify(port).reviewFailed(order());
        verify(port).reviewed(other);
        verify(port, never()).reviewed(order());
    }

    @Test
    void missingAuthoritativeStateAfterProjectionIsNotPresentedAsCurrent() {
        when(port.find("order")).thenReturn(Mono.just(order().withInventory(state("RESERVED", "NONE"))));
        when(inventory.find("order")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.get("owner", "order"))
            .expectErrorMatches(error -> error instanceof DomainFailure failure && failure.status() == 503)
            .verify();
    }

    @Test
    void invalidEnvelopeIdentifiersAndTicketNumbersAreRejected() {
        for (String id : Arrays.asList(null, "")) {
            var envelope = new Envelope(id, "ReservationStateChanged", 1, "order", "order", "order", now,
                Map.of());
            StepVerifier.create(useCase.receive(envelope, state("RESERVED", "")))
                .expectError(DomainFailure.class).verify();
        }
        StepVerifier.create(useCase.receive(
            new Envelope("id", "ReservationStateChanged", 2, "order", "order", "order", now, Map.of()),
            state("RESERVED", ""))).expectError(DomainFailure.class).verify();
        StepVerifier
            .create(useCase.receive(Envelope.create("ReservationStateChanged", "wrong", Map.of(), now),
                state("RESERVED", "")))
            .expectError(DomainFailure.class).verify();
        StepVerifier
            .create(useCase.create("owner", "request-0001", "event", List.of("100001"), "owner", "", false))
            .expectError(DomainFailure.class).verify();
    }
}
