package com.ticketing.inventory.usecase;

import com.ticketing.inventory.model.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.mockito.Mockito.*;

class OutboxUseCaseTest {
    @Test void marksSentOnlyAfterSuccessfulPublicationAndSkipsLostLeases() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var store = mock(OutboxStore.class);
        var publisher = mock(MessagePublisher.class);
        var service = new OutboxUseCase(store, publisher, Clock.fixed(now, ZoneOffset.UTC));
        var first = new OutboxMessage("1", "ORDERS", "{}", null);
        var claimed = new OutboxMessage("1", "ORDERS", "{}", "lease");
        var other = new OutboxMessage("2", "ORDERS", "{}", null);
        when(store.coordinate(anyString(), eq(now))).thenReturn(Mono.just(new WorkLease(true, now.plusSeconds(30))));
        when(store.pending(now, 10)).thenReturn(Flux.just(first, other));
        when(store.claim(first, now)).thenReturn(Mono.just(claimed));
        when(store.claim(other, now)).thenReturn(Mono.empty());
        when(publisher.publish(claimed)).thenReturn(Mono.empty());
        when(store.sent(claimed, now)).thenReturn(Mono.empty());
        StepVerifier.create(service.publishPending(10)).expectNext(1L).verifyComplete();
        var order = inOrder(publisher, store);
        order.verify(publisher).publish(claimed); order.verify(store).sent(claimed, now);
        reset(publisher, store);
        when(store.coordinate(anyString(), eq(now))).thenReturn(Mono.just(new WorkLease(true, now.plusSeconds(30))));
        when(store.pending(now, 10)).thenReturn(Flux.just(first));
        when(store.claim(first, now)).thenReturn(Mono.just(claimed));
        when(publisher.publish(claimed)).thenReturn(Mono.error(new IllegalStateException("Unavailable")));
        StepVerifier.create(service.publishPending(10)).expectError(IllegalStateException.class).verify();
        verify(store, never()).sent(any(), any());
    }

    @Test void followerNeverQueriesPartitionsAndWaitsOnlyRemainingLease() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var store = mock(OutboxStore.class);
        var publisher = mock(MessagePublisher.class);
        var service = new OutboxUseCase(store, publisher, Clock.fixed(now, ZoneOffset.UTC));
        when(store.coordinate(anyString(), eq(now))).thenReturn(Mono.just(new WorkLease(false, now.plusSeconds(7))));
        StepVerifier.create(service.dispatch(100)).expectNext(new DispatchBatch(0, 7000)).verifyComplete();
        verify(store, never()).pending(any(), anyInt());
        verifyNoInteractions(publisher);
        when(store.coordinate(anyString(), eq(now))).thenReturn(Mono.just(new WorkLease(false, now.minusSeconds(1))));
        StepVerifier.create(service.dispatch(100)).expectNext(new DispatchBatch(0, 1)).verifyComplete();
    }
}
