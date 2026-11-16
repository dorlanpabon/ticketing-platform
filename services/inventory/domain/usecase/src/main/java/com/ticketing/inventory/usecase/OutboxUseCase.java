package com.ticketing.inventory.usecase;

import static com.ticketing.inventory.usecase.OutboxConstants.MINIMUM_DEFER_MILLIS;
import static com.ticketing.inventory.usecase.OutboxConstants.NO_DEFER_MILLIS;
import static com.ticketing.inventory.usecase.OutboxConstants.NO_PUBLICATIONS;

import com.ticketing.inventory.model.DispatchBatch;
import com.ticketing.inventory.model.MessagePublisher;
import com.ticketing.inventory.model.OutboxMessage;
import com.ticketing.inventory.model.OutboxStore;
import com.ticketing.inventory.model.WorkLease;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Dispatches claimed inventory events and schedules recovery after a failed publication.
 */
public final class OutboxUseCase {
    private final OutboxStore store;
    private final MessagePublisher publisher;
    private final Clock clock;
    private final OutboxDispatchPolicy policy;
    private final String owner = UUID.randomUUID().toString();

    public OutboxUseCase(OutboxStore store, MessagePublisher publisher, Clock clock,
        OutboxDispatchPolicy policy) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.policy = policy;
    }

    public Mono<Long> publishPending(int limit) {
        return dispatch(limit).map(DispatchBatch::published);
    }

    public Mono<DispatchBatch> dispatch(int limit) {
        return Mono.defer(
            () -> store.coordinate(owner, clock.instant()).flatMap(lease -> dispatchWithLease(limit, lease)));
    }

    private Mono<DispatchBatch> dispatchWithLease(int limit, WorkLease lease) {
        Duration remaining = Duration.between(clock.instant(), lease.until());
        if (!lease.acquired() || !remaining.isPositive()) {
            long deferMillis = Math.max(MINIMUM_DEFER_MILLIS, remaining.toMillis());
            return Mono.just(new DispatchBatch(NO_PUBLICATIONS, deferMillis));
        }
        Duration timeout = remaining.compareTo(policy.dispatchTimeout()) < 0 ? remaining
            : policy.dispatchTimeout();
        return publishOwned(limit).timeout(timeout).map(count -> new DispatchBatch(count, NO_DEFER_MILLIS));
    }

    private Mono<Long> publishOwned(int limit) {
        return store.pending(clock.instant(), limit)
            .flatMap(message -> store.claim(message, clock.instant()).flatMap(this::publishClaimed),
                policy.publishConcurrency())
            .count();
    }

    private Mono<OutboxMessage> publishClaimed(OutboxMessage message) {
        return publisher.publish(message).then(Mono.defer(() -> store.sent(message, clock.instant())))
            .thenReturn(message);
    }
}
