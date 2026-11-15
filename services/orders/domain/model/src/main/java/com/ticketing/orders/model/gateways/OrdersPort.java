package com.ticketing.orders.model.gateways;

import com.ticketing.orders.model.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
public interface OrdersPort {
    Mono<Order> create(Order order,String key,String fingerprint,Envelope message);
    Mono<Order> find(String id);
    Mono<Void> confirm(Order order,String key,Envelope message);
    Mono<Void> project(Reservation state,String messageId);
    Flux<Order> due();
    Mono<Void> reviewed(Order order);
    Mono<Void> reviewFailed(Order order);
}
