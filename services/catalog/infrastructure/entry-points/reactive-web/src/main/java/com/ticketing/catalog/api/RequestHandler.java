package com.ticketing.catalog.api;

import static com.ticketing.catalog.api.HttpConstants.CURSOR_QUERY_PARAMETER;
import static com.ticketing.catalog.api.HttpConstants.EVENT_ID_PATH_VARIABLE;
import static com.ticketing.catalog.api.HttpConstants.LIMIT_QUERY_PARAMETER;

import com.ticketing.catalog.api.dto.request.CreateEventRequest;
import com.ticketing.catalog.api.mapper.EventResponseMapper;
import com.ticketing.catalog.model.CatalogErrorCodes;
import com.ticketing.catalog.model.DomainFailure;
import com.ticketing.catalog.usecase.CatalogUseCase;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Maps catalog HTTP requests and responses to the event use cases.
 */
@Component
public final class RequestHandler {
    private final CatalogUseCase useCase;
    private final EventResponseMapper mapper;

    public RequestHandler(CatalogUseCase useCase, EventResponseMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        Identity identity = Identity.from(request);
        identity.requireAdmin();
        return request.bodyToMono(CreateEventRequest.class)
            .switchIfEmpty(Mono.error(new DomainFailure(400, CatalogErrorCodes.INVALID_BODY)))
            .flatMap(body -> useCase.create(identity.subject(),
                request.headers().firstHeader(HttpConstants.IDEMPOTENCY_HEADER), body.name(), body.venue(),
                body.startsAt(), body.capacity()))
            .flatMap(event -> ServerResponse.accepted()
                .location(URI.create(HttpConstants.EVENT_LOCATION_PREFIX + event.eventId()))
                .bodyValue(mapper.toResponse(event)));
    }

    public Mono<ServerResponse> find(ServerRequest request) {
        return useCase.find(request.pathVariable(EVENT_ID_PATH_VARIABLE))
            .flatMap(event -> ServerResponse.ok().bodyValue(mapper.toResponse(event)));
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        String cursor = request.queryParam(CURSOR_QUERY_PARAMETER).orElse(null);
        return request.queryParam(LIMIT_QUERY_PARAMETER).map(Integer::parseInt)
            .map(limit -> useCase.list(limit, cursor)).orElseGet(() -> useCase.listDefaultPage(cursor))
            .flatMap(page -> ServerResponse.ok().bodyValue(mapper.toResponse(page)));
    }
}
