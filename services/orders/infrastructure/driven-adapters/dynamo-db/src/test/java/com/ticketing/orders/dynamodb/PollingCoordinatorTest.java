package com.ticketing.orders.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

class PollingCoordinatorTest {
    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final MutableClock clock = new MutableClock();
    private final PollingCoordinator coordinator = new PollingCoordinator(client, "table", clock, "OUTBOX",
        10000);

    private void grant() {
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));
    }

    @Test
    void onlyPollsAfterBackoffAndRenewsBeforeLeaseExpiry() {
        grant();
        var calls = new AtomicInteger();
        Supplier<Flux<Integer>> empty = () -> {
            calls.incrementAndGet();
            return Flux.empty();
        };
        StepVerifier.create(coordinator.poll(empty)).verifyComplete();
        StepVerifier.create(coordinator.poll(empty)).verifyComplete();
        assertEquals(1, calls.get());
        clock.advance(1500);
        StepVerifier.create(coordinator.poll(() -> Flux.just(1))).expectNext(1).verifyComplete();
        clock.advance(800);
        StepVerifier.create(coordinator.poll(() -> Flux.just(2))).expectNext(2).verifyComplete();
        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
        clock.advance(30000);
        StepVerifier.create(coordinator.poll(empty)).verifyComplete();
        verify(client, times(2)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void aFollowerDoesNotRunQueriesOrHammerTheLease() {
        var held = ConditionalCheckFailedException.builder()
            .item(Map.of("leaseUntil", AttributeValue.fromN(Long.toString(clock.millis() + 30000)))).build();
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(held));
        var calls = new AtomicInteger();
        Supplier<Flux<Integer>> work = () -> {
            calls.incrementAndGet();
            return Flux.just(1);
        };
        StepVerifier.create(coordinator.poll(work)).verifyComplete();
        clock.advance(29000);
        StepVerifier.create(coordinator.poll(work)).verifyComplete();
        assertEquals(0, calls.get());
        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
        grant();
        clock.advance(2000);
        StepVerifier.create(coordinator.poll(work)).expectNext(1).verifyComplete();
        assertEquals(1, calls.get());
    }

    @Test
    void conflictsWithoutOldItemAndFailuresAreRetryable() {
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        StepVerifier.create(coordinator.poll(() -> Flux.just(1))).verifyComplete();
        clock.advance(31000);
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        StepVerifier.create(coordinator.poll(() -> Flux.just(1))).expectError(IllegalStateException.class)
            .verify();
        grant();
        clock.advance(1100);
        StepVerifier.create(coordinator.poll(() -> Flux.error(new IllegalArgumentException("work failed"))))
            .expectError(IllegalArgumentException.class).verify();
        clock.advance(1100);
        StepVerifier.create(coordinator.poll(() -> Flux.just(1))).expectNext(1).verifyComplete();
    }

    @Test
    void cancellationReleasesLocalInFlightGuardAndIdleDelayIsBounded() {
        grant();
        StepVerifier.create(coordinator.poll(Flux::never))
            .then(() -> StepVerifier.create(coordinator.poll(() -> Flux.just(9))).verifyComplete())
            .thenCancel().verify();
        StepVerifier.create(coordinator.poll(() -> Flux.just(1))).expectNext(1).verifyComplete();
        for (int iteration = 0; iteration < 8; iteration++) {
            clock.advance(11000);
            StepVerifier.create(coordinator.poll(Flux::empty)).verifyComplete();
        }
        clock.advance(11000);
        StepVerifier.create(coordinator.poll(() -> Flux.just(2))).expectNext(2).verifyComplete();
    }

    @Test
    void aLongIdleBackoffRenewsLeadershipWithoutQuerying() {
        grant();
        var slow = new PollingCoordinator(client, "table", clock, "RECONCILIATION", 60000);
        var queries = new AtomicInteger();
        Supplier<Flux<Integer>> work = () -> {
            queries.incrementAndGet();
            return Flux.empty();
        };
        for (int iteration = 0; iteration < 7; iteration++) {
            clock.advance(70000);
            StepVerifier.create(slow.poll(work)).verifyComplete();
        }
        clearInvocations(client);
        clock.advance(21000);
        StepVerifier.create(slow.poll(work)).verifyComplete();
        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
        assertEquals(7, queries.get());
        clock.advance(21000);
        StepVerifier.create(slow.poll(work)).verifyComplete();
        assertEquals(7, queries.get());
        clock.advance(19000);
        StepVerifier.create(slow.poll(work)).verifyComplete();
        assertEquals(8, queries.get());
    }

    private static final class MutableClock extends Clock {
        private Instant value = Instant.parse("2026-11-16T00:00:00Z");

        void advance(long millis) {
            value = value.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }
}
