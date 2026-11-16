package com.ticketing.inventory.model;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface OutboxStore {
    Mono<WorkLease> coordinate(String owner, Instant now);

    Flux<OutboxMessage> pending(Instant now, int limit);

    Mono<OutboxMessage> claim(OutboxMessage message, Instant now);

    Mono<Void> sent(OutboxMessage message, Instant now);
}
