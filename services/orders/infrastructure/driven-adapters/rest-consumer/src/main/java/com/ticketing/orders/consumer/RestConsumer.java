package com.ticketing.orders.consumer;

import com.ticketing.orders.model.*;
import com.ticketing.orders.model.gateways.InventoryPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Map;
@Component
public final class RestConsumer implements InventoryPort {
    private final WebClient client;
    public RestConsumer(WebClient client) { this.client=client; }
    @Override public Mono<Reservation> reserve(Order order) {
        return client.put().uri("/internal/reservations/{id}",order.orderId()).bodyValue(ObjectRequest.from(order))
                .retrieve().bodyToMono(ObjectResponse.class).map(ObjectResponse::toReservation).timeout(Duration.ofSeconds(2));
    }
    @Override public Mono<Reservation> find(String id) {
        return client.get().uri("/internal/reservations/{id}",id).exchangeToMono(response -> {
            if(response.statusCode().equals(org.springframework.http.HttpStatus.NOT_FOUND)) return response.releaseBody().then(Mono.empty());
            if(!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(Mono.error(new DomainFailure(503,"INVENTORY_UNAVAILABLE")));
            return response.bodyToMono(ObjectResponse.class).map(ObjectResponse::toReservation);
        }).timeout(Duration.ofSeconds(2)).onErrorMap(error -> error instanceof DomainFailure ? error : new DomainFailure(503,"INVENTORY_UNAVAILABLE"));
    }
}
