package com.ticketing.orders.model.gateways;

import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.OrderSubmission;
import com.ticketing.orders.model.Reservation;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OrdersPort {
    Mono<OrderSubmission> create(Order order, String key, String fingerprint, Envelope message);

    Mono<Order> find(String id);

    Mono<Void> confirm(Order order, String key, Envelope message);

    Mono<Void> project(Reservation state, String messageId);

    Flux<Order> due();

    Mono<Void> reviewed(Order order);

    Mono<Void> reviewFailed(Order order);
}
