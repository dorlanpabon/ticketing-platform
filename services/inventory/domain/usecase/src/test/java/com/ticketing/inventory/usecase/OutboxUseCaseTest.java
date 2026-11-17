package com.ticketing.inventory.usecase;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.inventory.model.DispatchBatch;
import com.ticketing.inventory.model.MessagePublisher;
import com.ticketing.inventory.model.OutboxMessage;
import com.ticketing.inventory.model.OutboxStore;
import com.ticketing.inventory.model.WorkLease;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeoutException;

class OutboxUseCaseTest {
    private static final OutboxDispatchPolicy POLICY = new OutboxDispatchPolicy(8, Duration.ofSeconds(15));

    @Test
    void marksSentOnlyAfterSuccessfulPublicationAndSkipsLostLeases() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var store = mock(OutboxStore.class);
        var publisher = mock(MessagePublisher.class);
        var service = new OutboxUseCase(store, publisher, Clock.fixed(now, ZoneOffset.UTC), POLICY);
        var first = new OutboxMessage("1", "ORDERS", "{}", null);
        var claimed = new OutboxMessage("1", "ORDERS", "{}", "lease");
        var other = new OutboxMessage("2", "ORDERS", "{}", null);
        when(store.coordinate(anyString(), eq(now)))
            .thenReturn(Mono.just(new WorkLease(true, now.plusSeconds(30))));
        when(store.pending(now, 10)).thenReturn(Flux.just(first, other));
        when(store.claim(first, now)).thenReturn(Mono.just(claimed));
        when(store.claim(other, now)).thenReturn(Mono.empty());
        when(publisher.publish(claimed)).thenReturn(Mono.empty());
        when(store.sent(claimed, now)).thenReturn(Mono.empty());
        StepVerifier.create(service.publishPending(10)).expectNext(1L).verifyComplete();
        var order = inOrder(publisher, store);
        order.verify(publisher).publish(claimed);
        order.verify(store).sent(claimed, now);
        reset(publisher, store);
        when(store.coordinate(anyString(), eq(now)))
            .thenReturn(Mono.just(new WorkLease(true, now.plusSeconds(30))));
        when(store.pending(now, 10)).thenReturn(Flux.just(first));
        when(store.claim(first, now)).thenReturn(Mono.just(claimed));
        when(publisher.publish(claimed)).thenReturn(Mono.error(new IllegalStateException("Unavailable")));
        StepVerifier.create(service.publishPending(10)).expectError(IllegalStateException.class).verify();
        verify(store, never()).sent(any(), any());
    }

    @Test
    void followerNeverQueriesPartitionsAndWaitsOnlyRemainingLease() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var store = mock(OutboxStore.class);
        var publisher = mock(MessagePublisher.class);
        var service = new OutboxUseCase(store, publisher, Clock.fixed(now, ZoneOffset.UTC), POLICY);
        when(store.coordinate(anyString(), eq(now)))
            .thenReturn(Mono.just(new WorkLease(false, now.plusSeconds(7))));
        StepVerifier.create(service.dispatch(100)).expectNext(new DispatchBatch(0, 7000)).verifyComplete();
        verify(store, never()).pending(any(), anyInt());
        verifyNoInteractions(publisher);
        when(store.coordinate(anyString(), eq(now)))
            .thenReturn(Mono.just(new WorkLease(false, now.minusSeconds(1))));
        StepVerifier.create(service.dispatch(100)).expectNext(new DispatchBatch(0, 1)).verifyComplete();

        when(store.coordinate(anyString(), eq(now))).thenReturn(Mono.just(new WorkLease(true, now)));
        StepVerifier.create(service.dispatch(100)).expectNext(new DispatchBatch(0, 1)).verifyComplete();
        verify(store, never()).pending(any(), anyInt());
    }

    @Test
    void dispatchCannotWaitPastTheRemainingCoordinatorLease() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var store = mock(OutboxStore.class);
        var publisher = mock(MessagePublisher.class);
        var service = new OutboxUseCase(store, publisher, Clock.fixed(now, ZoneOffset.UTC), POLICY);
        var message = new OutboxMessage("1", "ORDERS", "{}", "lease");
        when(store.coordinate(anyString(), eq(now)))
            .thenReturn(Mono.just(new WorkLease(true, now.plusSeconds(2))));
        when(store.pending(now, 10)).thenReturn(Flux.just(message));
        when(store.claim(message, now)).thenReturn(Mono.just(message));
        when(publisher.publish(message)).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(() -> service.dispatch(10)).expectSubscription()
            .expectNoEvent(Duration.ofMillis(1999)).thenAwait(Duration.ofMillis(1))
            .expectError(TimeoutException.class).verify(Duration.ofSeconds(5));
        verify(store, never()).sent(any(), any());
    }

    @Test
    void rejectsInvalidDispatchSettings() {
        assertThrows(IllegalArgumentException.class,
            () -> new OutboxDispatchPolicy(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new OutboxDispatchPolicy(1, null));
        assertThrows(IllegalArgumentException.class, () -> new OutboxDispatchPolicy(1, Duration.ZERO));
    }
}
