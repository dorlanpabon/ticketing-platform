package com.ticketing.inventory.usecase;

import com.ticketing.inventory.model.MessagePublisher;
import com.ticketing.inventory.model.OutboxStore;
import com.ticketing.inventory.model.DispatchBatch;
import java.time.Clock;
import java.time.Duration;
import reactor.core.publisher.Mono;

public final class OutboxUseCase {
    private final OutboxStore store;
    private final MessagePublisher publisher;
    private final Clock clock;
    private final String owner = java.util.UUID.randomUUID().toString();

    public OutboxUseCase(OutboxStore store, MessagePublisher publisher, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
    }

    public Mono<Long> publishPending(int limit) {
        return dispatch(limit).map(DispatchBatch::published);
    }

    public Mono<DispatchBatch> dispatch(int limit) {
        return Mono.defer(() -> store.coordinate(owner, clock.instant()).flatMap(lease -> {
            if (!lease.acquired()) return Mono.just(new DispatchBatch(0,
                    Math.max(1, Duration.between(clock.instant(), lease.until()).toMillis())));
            return publishOwned(limit).timeout(Duration.ofSeconds(15)).map(count -> new DispatchBatch(count, 0));
        }));
    }

    private Mono<Long> publishOwned(int limit) {
        return store.pending(clock.instant(), limit)
                .flatMap(message -> store.claim(message, clock.instant())
                        .flatMap(claimed -> publisher.publish(claimed)
                                .then(Mono.defer(() -> store.sent(claimed, clock.instant()))).thenReturn(claimed)), 8)
                .count();
    }
}
