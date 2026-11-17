package com.ticketing.catalog.model.gateways;

import com.ticketing.catalog.model.OutboxEntry;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OutboxPort {
    Flux<OutboxEntry> claim();

    Mono<Void> acknowledge(OutboxEntry entry);
}
