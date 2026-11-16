package com.ticketing.orders.consumer.adapter;

import static com.ticketing.orders.consumer.utils.HttpClientConstants.INVENTORY_UNAVAILABLE;
import static com.ticketing.orders.consumer.utils.HttpClientConstants.RESERVATION_PATH;

import com.ticketing.orders.consumer.config.HttpClientProperties;
import com.ticketing.orders.consumer.dto.response.ReservationResponse;
import com.ticketing.orders.consumer.mapper.ReservationMapper;
import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.model.gateways.InventoryPort;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reads reservation state through the internal Inventory HTTP API.
 */
@Component("restConsumer")
public final class HttpAdapter implements InventoryPort {
    private final WebClient client;
    private final Duration requestTimeout;
    private final ReservationMapper mapper;

    public HttpAdapter(WebClient client, HttpClientProperties properties, ReservationMapper mapper) {
        this.client = client;
        this.requestTimeout = properties.requestTimeout();
        this.mapper = mapper;
    }

    @Override
    public Mono<Reservation> reserve(Order order) {
        return client.put().uri(RESERVATION_PATH, order.orderId()).bodyValue(mapper.toRequest(order))
            .retrieve().bodyToMono(ReservationResponse.class).map(mapper::toDomain).timeout(requestTimeout);
    }

    @Override
    public Mono<Reservation> find(String id) {
        return client.get().uri(RESERVATION_PATH, id).exchangeToMono(response -> {
            if (response.statusCode().equals(HttpStatus.NOT_FOUND)) {
                return response.releaseBody().then(Mono.empty());
            }
            if (!response.statusCode().is2xxSuccessful()) {
                return response.releaseBody().then(Mono.error(new DomainFailure(503, INVENTORY_UNAVAILABLE)));
            }
            return response.bodyToMono(ReservationResponse.class).map(mapper::toDomain);
        }).timeout(requestTimeout).onErrorMap(
            error -> error instanceof DomainFailure ? error : new DomainFailure(503, INVENTORY_UNAVAILABLE));
    }
}
