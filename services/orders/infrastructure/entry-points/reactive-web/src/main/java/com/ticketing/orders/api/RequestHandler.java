package com.ticketing.orders.api;

import com.ticketing.orders.api.dto.request.ComplimentaryOrderRequest;
import com.ticketing.orders.api.dto.request.PurchaseRequest;
import com.ticketing.orders.api.mapper.OrderResponseMapper;
import com.ticketing.orders.api.utils.HttpConstants;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.usecase.OrdersUseCase;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Maps order HTTP requests and responses to the purchase and confirmation use cases.
 */
@Component("handler")
public final class RequestHandler {
    private final OrdersUseCase useCase;
    private final OrderResponseMapper responseMapper;

    public RequestHandler(OrdersUseCase useCase, OrderResponseMapper responseMapper) {
        this.useCase = useCase;
        this.responseMapper = responseMapper;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        Identity identity = Identity.from(request);
        return request.bodyToMono(PurchaseRequest.class)
            .switchIfEmpty(Mono.error(new DomainFailure(400, HttpConstants.INVALID_BODY)))
            .flatMap(body -> useCase.create(identity.subject(),
                request.headers().firstHeader(HttpConstants.IDEMPOTENCY_HEADER), body.eventId(),
                body.ticketIds(), identity.subject(), "", false))
            .flatMap(order -> ServerResponse.accepted()
                .location(URI.create(HttpConstants.ORDERS_PATH + "/" + order.orderId()))
                .bodyValue(responseMapper.toResponse(order)));
    }

    public Mono<ServerResponse> complimentary(ServerRequest request) {
        Identity identity = Identity.from(request);
        identity.requireAdmin();
        return request.bodyToMono(ComplimentaryOrderRequest.class)
            .switchIfEmpty(Mono.error(new DomainFailure(400, HttpConstants.INVALID_BODY)))
            .flatMap(body -> useCase.create(body.beneficiaryId(),
                request.headers().firstHeader(HttpConstants.IDEMPOTENCY_HEADER), body.eventId(),
                body.ticketIds(), identity.subject(), body.reason(), true))
            .flatMap(order -> ServerResponse.accepted()
                .location(URI.create(HttpConstants.ORDERS_PATH + "/" + order.orderId()))
                .bodyValue(responseMapper.toResponse(order)));
    }

    public Mono<ServerResponse> find(ServerRequest request) {
        return useCase
            .get(Identity.from(request).subject(), request.pathVariable(HttpConstants.ORDER_ID_VARIABLE))
            .flatMap(order -> ServerResponse.ok().bodyValue(responseMapper.toResponse(order)));
    }

    public Mono<ServerResponse> confirm(ServerRequest request) {
        return useCase
            .confirm(Identity.from(request).subject(), request.pathVariable(HttpConstants.ORDER_ID_VARIABLE),
                request.headers().firstHeader(HttpConstants.IDEMPOTENCY_HEADER))
            .flatMap(order -> ServerResponse.accepted().bodyValue(responseMapper.toResponse(order)));
    }
}
