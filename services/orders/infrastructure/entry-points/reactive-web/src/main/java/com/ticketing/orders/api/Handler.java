package com.ticketing.orders.api;

import com.ticketing.orders.usecase.OrdersUseCase;
import com.ticketing.orders.model.DomainFailure;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.util.List;
@Component
public final class Handler {
    private final OrdersUseCase useCase;
    public Handler(OrdersUseCase useCase) { this.useCase=useCase; }
    public record Purchase(String eventId,List<String> ticketIds) { }
    public record Complimentary(String eventId,List<String> ticketIds,String beneficiaryId,String reason) { }
    public Mono<ServerResponse> create(ServerRequest request) {
        Identity identity=Identity.from(request);
        return request.bodyToMono(Purchase.class).switchIfEmpty(Mono.error(new DomainFailure(400,"INVALID_BODY")))
                .flatMap(body -> useCase.create(identity.subject(),request.headers().firstHeader("Idempotency-Key"),body.eventId(),body.ticketIds(),identity.subject(),"",false))
                .flatMap(order -> ServerResponse.accepted().location(URI.create("/api/v1/orders/"+order.orderId())).bodyValue(order));
    }
    public Mono<ServerResponse> complimentary(ServerRequest request) {
        Identity identity=Identity.from(request); identity.requireAdmin();
        return request.bodyToMono(Complimentary.class).switchIfEmpty(Mono.error(new DomainFailure(400,"INVALID_BODY")))
                .flatMap(body -> useCase.create(body.beneficiaryId(),request.headers().firstHeader("Idempotency-Key"),body.eventId(),body.ticketIds(),identity.subject(),body.reason(),true))
                .flatMap(order -> ServerResponse.accepted().location(URI.create("/api/v1/orders/"+order.orderId())).bodyValue(order));
    }
    public Mono<ServerResponse> find(ServerRequest request) { return useCase.get(Identity.from(request).subject(),request.pathVariable("id")).flatMap(order -> ServerResponse.ok().bodyValue(order)); }
    public Mono<ServerResponse> confirm(ServerRequest request) { return useCase.confirm(Identity.from(request).subject(),request.pathVariable("id"),request.headers().firstHeader("Idempotency-Key")).flatMap(order -> ServerResponse.accepted().bodyValue(order)); }
}
