package com.ticketing.orders.model.gateways;

import reactor.core.publisher.Mono;

public interface OrderEnqueuePort {
    Mono<Void> enqueue(String messageId);
}
