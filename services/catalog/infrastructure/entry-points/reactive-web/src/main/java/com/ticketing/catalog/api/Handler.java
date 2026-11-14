package com.ticketing.catalog.api;

import com.ticketing.catalog.usecase.CatalogUseCase;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.time.Instant;
@Component
public final class Handler {
    private final CatalogUseCase useCase;
    public Handler(CatalogUseCase useCase) { this.useCase=useCase; }
    public record CreateEvent(String name,String venue,Instant startsAt,int capacity) { }
    public Mono<ServerResponse> create(ServerRequest request) {
        Identity identity=Identity.from(request); identity.requireAdmin();
        return request.bodyToMono(CreateEvent.class).flatMap(body -> useCase.create(identity.subject(),request.headers().firstHeader("Idempotency-Key"),body.name(),body.venue(),body.startsAt(),body.capacity()))
                .flatMap(event -> ServerResponse.accepted().location(URI.create("/api/v1/events/"+event.eventId())).bodyValue(event));
    }
    public Mono<ServerResponse> find(ServerRequest request) { return useCase.find(request.pathVariable("id")).flatMap(event -> ServerResponse.ok().bodyValue(event)); }
    public Mono<ServerResponse> list(ServerRequest request) { return useCase.list(Integer.parseInt(request.queryParam("limit").orElse("50")),request.queryParam("cursor").orElse(null)).flatMap(page -> ServerResponse.ok().bodyValue(page)); }
}
