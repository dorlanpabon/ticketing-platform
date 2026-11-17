package com.ticketing.inventory.api;

import com.ticketing.inventory.api.dto.request.ConfirmationRequest;
import com.ticketing.inventory.api.dto.request.ReserveTicketsRequest;
import com.ticketing.inventory.api.mapper.HttpMapper;
import com.ticketing.inventory.api.utils.HttpConstants;
import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.usecase.InventoryUseCase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Exposes ticket availability and internal reservation queries over HTTP.
 */
@Component
public final class RequestHandler {

    private final InventoryUseCase inventory;
    private final HttpMapper httpMapper;
    private final String authMode;
    private final String profiles;
    private final int defaultPageSize;

    public RequestHandler(InventoryUseCase inventory, HttpMapper httpMapper,
        @Value("${auth.mode:trusted-gateway}") String authMode,
        @Value("${spring.profiles.active:}") String profiles,
        @Value("${inventory.default-page-size}") int defaultPageSize) {
        this.inventory = inventory;
        this.httpMapper = httpMapper;
        this.authMode = authMode;
        this.profiles = profiles;
        this.defaultPageSize = defaultPageSize;
    }

    public Mono<ServerResponse> reserve(ServerRequest request) {
        return request.bodyToMono(ReserveTicketsRequest.class)
            .switchIfEmpty(Mono.error(new InventoryException(HttpConstants.INVALID_BODY, 400)))
            .map(body -> httpMapper.toIntent(body, request.pathVariable(HttpConstants.ORDER_ID)))
            .flatMap(inventory::reserve).map(httpMapper::toReservationResponse)
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return inventory.get(request.pathVariable(HttpConstants.ORDER_ID))
            .map(httpMapper::toReservationResponse).flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> confirm(ServerRequest request) {
        return request.bodyToMono(ConfirmationRequest.class)
            .switchIfEmpty(Mono.error(new InventoryException(HttpConstants.INVALID_BODY, 400)))
            .flatMap(body -> inventory.confirm(request.pathVariable(HttpConstants.ORDER_ID),
                body.customerId(), body.commandId()))
            .map(httpMapper::toReservationResponse).flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> availability(ServerRequest request) {
        return authenticate(request)
            .then(inventory.availability(request.pathVariable(HttpConstants.EVENT_ID)))
            .map(httpMapper::toAvailabilityResponse).flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> tickets(ServerRequest request) {
        return authenticate(request).then(Mono.defer(() -> {
            int limit;
            try {
                limit = request.queryParam(HttpConstants.PAGE_LIMIT).map(Integer::parseInt)
                    .orElse(defaultPageSize);
            } catch (NumberFormatException error) {
                return Mono.error(new InventoryException(HttpConstants.INVALID_PAGE_SIZE, 400));
            }
            return inventory.tickets(request.pathVariable(HttpConstants.EVENT_ID), limit,
                request.queryParam(HttpConstants.CURSOR).orElse(null));
        })).map(httpMapper::toTicketPageResponse).flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<Void> authenticate(ServerRequest request) {
        boolean authenticated;
        if (HttpConstants.LOCAL_AUTH_MODE.equals(authMode)
            && Set.of(profiles.split(",")).contains(HttpConstants.LOCAL_AUTH_MODE)) {
            authenticated = Set
                .of(HttpConstants.LOCAL_CUSTOMER_ONE_TOKEN, HttpConstants.LOCAL_CUSTOMER_TWO_TOKEN,
                    HttpConstants.LOCAL_ADMIN_TOKEN)
                .contains(request.headers().firstHeader(HttpConstants.AUTHORIZATION_HEADER) == null ? ""
                    : request.headers().firstHeader(HttpConstants.AUTHORIZATION_HEADER));
        } else {
            String subject = request.headers().firstHeader(HttpConstants.AUTHENTICATED_SUBJECT_HEADER);
            authenticated = HttpConstants.TRUSTED_GATEWAY_AUTH_MODE.equals(authMode) && subject != null
                && !subject.isBlank();
        }
        return authenticated ? Mono.empty()
            : Mono.error(new InventoryException(HttpConstants.UNAUTHENTICATED, 401));
    }
}
