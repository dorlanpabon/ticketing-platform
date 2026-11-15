package com.ticketing.orders.model.gateways;

import com.ticketing.orders.model.*;
import reactor.core.publisher.Mono;
public interface InventoryPort {
    Mono<Reservation> reserve(Order order);
    Mono<Reservation> find(String id);
}
