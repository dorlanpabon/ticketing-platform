package com.ticketing.inventory.model;

import reactor.core.publisher.Mono;

public interface MessagePublisher {
    Mono<Void> publish(OutboxMessage message);
}
