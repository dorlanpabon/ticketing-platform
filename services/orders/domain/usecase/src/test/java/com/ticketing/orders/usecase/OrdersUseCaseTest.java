package com.ticketing.orders.usecase;

import com.ticketing.orders.model.*;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.gateways.*;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class OrdersUseCaseTest {
    final OrdersPort port=mock(OrdersPort.class);
    final InventoryPort inventory=mock(InventoryPort.class);
    final Instant now=Instant.parse("2026-11-16T00:00:00Z");
    final OrdersUseCase useCase=new OrdersUseCase(port,inventory,Clock.fixed(now,ZoneOffset.UTC));
    Order order() { return new Order("order","event","owner",List.of("1"),List.of(),"AVAILABLE","RESERVING","",0,now,now.plusSeconds(600),"PURCHASE","owner",""); }
    Reservation state(String status,String resolution) { return new Reservation("order","event","owner",status,resolution,1,List.of("1"),status.equals("AVAILABLE")?List.of():List.of("1"),now.plusSeconds(600)); }
    @Test void durableAcceptancePreservesOriginalDeadlineAndOutboxWithoutCallingInventory() {
        when(port.create(any(),anyString(),anyString(),any())).thenAnswer(call -> {
            Order saved=call.getArgument(0); Envelope message=call.getArgument(3);
            assertEquals(saved.orderId(),message.aggregateId());
            assertEquals(saved.orderId(),message.payload().get("orderId"));
            assertEquals(saved.requestedAt().toString(),message.payload().get("requestedAt"));
            assertEquals(saved.expiresAt().toString(),message.payload().get("expiresAt"));
            assertEquals(now,message.occurredAt());
            return Mono.just(saved);
        });
        StepVerifier.create(useCase.create("owner","request-0001","event",List.of("2","1"),"owner","",false))
                .assertNext(saved -> { assertEquals("RESERVING",saved.workflowStatus()); assertEquals(now,saved.requestedAt()); assertEquals(now.plusSeconds(600),saved.expiresAt()); assertEquals(List.of("1","2"),saved.requestedTicketIds()); }).verifyComplete();
        verify(port).create(any(),anyString(),anyString(),argThat(message -> message.type().equals("PurchaseRequested") && message.payload().get("ticketIds").equals(List.of("1","2"))));
        verifyNoInteractions(inventory);
    }
    @Test void acceptanceWaitsForDurablePersistence() {
        Sinks.One<Order> committed=Sinks.one();
        Order saved=order();
        when(port.create(any(),anyString(),anyString(),any())).thenReturn(committed.asMono());
        StepVerifier.withVirtualTime(() -> useCase.create("owner","request-0001","event",List.of("1"),"owner","",false))
                .expectSubscription().expectNoEvent(Duration.ofSeconds(1))
                .then(() -> assertEquals(Sinks.EmitResult.OK,committed.tryEmitValue(saved)))
                .expectNext(saved).verifyComplete();
        verifyNoInteractions(inventory);
    }
    @Test void failedPersistenceCannotProduceAcceptance() {
        var failure=new DomainFailure(503,"PERSISTENCE_UNAVAILABLE");
        when(port.create(any(),anyString(),anyString(),any())).thenReturn(Mono.error(failure));
        StepVerifier.create(useCase.create("owner","request-0001","event",List.of("1"),"owner","",false))
                .expectErrorMatches(error -> error==failure).verify();
        verifyNoInteractions(inventory);
    }
    @Test void idempotentReplayReturnsOriginalTerminalSnapshotAndDeadline() {
        Order saved=new Order("order","event","owner",List.of("1"),List.of("1"),"SOLD","COMPLETED","NONE",3,
                now.minusSeconds(45),now.plusSeconds(555),"PURCHASE","owner","");
        when(port.create(any(),anyString(),anyString(),any())).thenReturn(Mono.just(saved));
        StepVerifier.create(useCase.create("owner","request-0001","event",List.of("1"),"owner","",false))
                .expectNext(saved).verifyComplete();
        verifyNoInteractions(inventory);
    }
    @Test void invalidSelectionsNeverReachPersistence() {
        List<List<String>> invalid=Arrays.asList(null,List.of(),List.of("1","1"),List.of("01"),List.of("0"),List.of("-1"),Arrays.asList("1",null),java.util.stream.IntStream.rangeClosed(1,11).mapToObj(Integer::toString).toList());
        for(var tickets:invalid) StepVerifier.create(useCase.create("owner","request-0001","event",tickets,"owner","",false)).expectError(DomainFailure.class).verify();
        verifyNoInteractions(port,inventory);
    }
    @Test void complimentaryRequiresReasonAndCreatesDistinctCommand() {
        StepVerifier.create(useCase.create("owner","request-0001","event",List.of("1"),"admin","",true)).expectError(DomainFailure.class).verify();
        when(port.create(any(),anyString(),anyString(),any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        StepVerifier.create(useCase.create("owner","request-0002","event",List.of("1"),"admin","speaker",true)).expectNextCount(1).verifyComplete();
        verify(port).create(any(),anyString(),anyString(),argThat(message -> message.type().equals("ComplimentaryRequested")));
        verifyNoInteractions(inventory);
    }
    @Test void ownerIsCheckedBeforeInventoryAndDependencyErrorsPropagate() {
        when(port.find("order")).thenReturn(Mono.just(order()));
        StepVerifier.create(useCase.get("intruder","order")).expectErrorMatches(error -> ((DomainFailure)error).status()==404).verify();
        verifyNoInteractions(inventory);
        when(inventory.find("order")).thenReturn(Mono.error(new DomainFailure(503,"INVENTORY_UNAVAILABLE")));
        StepVerifier.create(useCase.get("owner","order")).expectErrorMatches(error -> ((DomainFailure)error).status()==503).verify();
    }
    @Test void pendingIntentCanBeObservedBeforeInventoryExists() {
        when(port.find("order")).thenReturn(Mono.just(order())); when(inventory.find("order")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.get("owner","order")).expectNext(order()).verifyComplete();
        StepVerifier.create(useCase.confirm("owner","order","confirm-0001")).expectErrorMatches(error -> ((DomainFailure)error).status()==409).verify();
    }
    @Test void confirmUsesStableCommandIdAcrossHttpKeys() {
        when(port.find("order")).thenReturn(Mono.just(order())); when(inventory.find("order")).thenReturn(Mono.just(state("RESERVED","")));
        when(port.confirm(any(),anyString(),any())).thenReturn(Mono.empty());
        for(String key:List.of("confirm-0001","confirm-0002")) StepVerifier.create(useCase.confirm("owner","order",key)).expectNextCount(1).verifyComplete();
        verify(port,times(2)).confirm(any(),anyString(),argThat(message -> message.messageId().equals("confirm-order") && message.payload().get("commandId").equals("confirm-order")));
    }
    @Test void soldConfirmationIsIdempotentAndDeadlineEqualityExpires() {
        when(port.find("order")).thenReturn(Mono.just(order())); when(inventory.find("order")).thenReturn(Mono.just(state("SOLD","")));
        when(port.confirm(any(),anyString(),any())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.confirm("owner","order","confirm-0001")).expectNextCount(1).verifyComplete();
        verify(port).confirm(any(),anyString(),any());
        when(inventory.find("order")).thenReturn(Mono.just(state("RESERVED","")));
        OrdersUseCase deadline=new OrdersUseCase(port,inventory,Clock.fixed(now.plusSeconds(600),ZoneOffset.UTC));
        StepVerifier.create(deadline.confirm("owner","order","confirm-0002")).expectErrorMatches(error -> ((DomainFailure)error).status()==410).verify();
    }
    @Test void rejectsUnsupportedEnvelopeAndReconcilesRecoverableOrders() {
        Envelope wrong=Envelope.create("Other","order",Map.of(),now);
        StepVerifier.create(useCase.receive(wrong,state("RESERVED",""))).expectError(DomainFailure.class).verify();
        Envelope valid=Envelope.create("ReservationStateChanged","order",Map.of(),now);
        when(port.project(any(),anyString())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.receive(valid,state("RESERVED",""))).verifyComplete();
        when(port.due()).thenReturn(Flux.just(order())); when(inventory.find("order")).thenReturn(Mono.just(state("SOLD",""))); when(port.reviewed(any())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete(); verify(port).reviewed(order());
    }
    @Test void absentInventoryStateIsRecoveredFromTheOriginalImmutableIntent() {
        when(port.due()).thenReturn(Flux.just(order()));
        when(inventory.find("order")).thenReturn(Mono.empty());
        when(inventory.reserve(order())).thenReturn(Mono.just(state("RESERVED","NONE")));
        when(port.project(any(),anyString())).thenReturn(Mono.empty());
        when(port.reviewed(order())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete();
        verify(inventory).reserve(order());
    }

    @Test void failedRecoveryRetainsTheDueOrderAndCanSucceedOnTheNextCycle() {
        when(port.due()).thenReturn(Flux.just(order()));
        when(inventory.find("order")).thenReturn(Mono.error(new IllegalStateException("temporarily unavailable")));
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

    @Test void oneUnavailableReservationDoesNotStarveOtherDueOrders() {
        Order other = new Order("other", "event", "owner", List.of("2"), List.of(), "AVAILABLE", "RESERVING", "", 0,
                now, now.plusSeconds(600), "PURCHASE", "owner", "");
        Reservation recovered = new Reservation("other", "event", "owner", "RESERVED", "NONE", 1,
                List.of("2"), List.of("2"), now.plusSeconds(600));
        when(port.due()).thenReturn(Flux.just(order(), other));
        when(inventory.find("order")).thenReturn(Mono.error(new IllegalStateException("unavailable")));
        when(port.reviewFailed(order())).thenReturn(Mono.empty());
        when(inventory.find("other")).thenReturn(Mono.just(recovered));
        when(port.project(eq(recovered), anyString())).thenReturn(Mono.empty());
        when(port.reviewed(other)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.reconcile()).verifyComplete();
        verify(port).reviewFailed(order()); verify(port).reviewed(other);
        verify(port, never()).reviewed(order());
    }
    @Test void missingAuthoritativeStateAfterProjectionIsNotPresentedAsCurrent() {
        when(port.find("order")).thenReturn(Mono.just(order().withInventory(state("RESERVED","NONE"))));
        when(inventory.find("order")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.get("owner","order")).expectErrorMatches(error -> error instanceof DomainFailure failure && failure.status()==503).verify();
    }
    @Test void invalidEnvelopeIdentifiersAndTicketNumbersAreRejected() {
        for(String id:Arrays.asList(null,"")) {
            var envelope=new Envelope(id,"ReservationStateChanged",1,"order","order","order",now,Map.of());
            StepVerifier.create(useCase.receive(envelope,state("RESERVED",""))).expectError(DomainFailure.class).verify();
        }
        StepVerifier.create(useCase.receive(new Envelope("id","ReservationStateChanged",2,"order","order","order",now,Map.of()),state("RESERVED",""))).expectError(DomainFailure.class).verify();
        StepVerifier.create(useCase.receive(Envelope.create("ReservationStateChanged","wrong",Map.of(),now),state("RESERVED",""))).expectError(DomainFailure.class).verify();
        StepVerifier.create(useCase.create("owner","request-0001","event",List.of("100001"),"owner","",false)).expectError(DomainFailure.class).verify();
    }
}
