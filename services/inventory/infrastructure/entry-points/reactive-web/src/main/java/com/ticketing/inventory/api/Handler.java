package com.ticketing.inventory.api;

import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.ReservationRequest;
import com.ticketing.inventory.usecase.InventoryUseCase;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Component
public final class Handler {
    private final InventoryUseCase inventory;
    private final String authMode;
    private final String profiles;

    public Handler(InventoryUseCase inventory, @Value("${auth.mode:trusted-gateway}") String authMode,
                   @Value("${spring.profiles.active:}") String profiles) {
        this.inventory = inventory;
        this.authMode = authMode;
        this.profiles = profiles;
    }

    public Mono<ServerResponse> reserve(ServerRequest request) {
        return request.bodyToMono(ReservationRequest.class)
                .switchIfEmpty(Mono.error(new InventoryException("INVALID_BODY", 400)))
                .map(body -> new ReservationRequest(request.pathVariable("orderId"), body.eventId(), body.customerId(),
                        body.ticketIds(), body.requestedAt(), body.expiresAt(), body.type(), body.actorId(), body.reason()))
                .flatMap(inventory::reserve).flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return inventory.get(request.pathVariable("orderId")).flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> confirm(ServerRequest request) {
        return request.bodyToMono(Confirmation.class).switchIfEmpty(Mono.error(new InventoryException("INVALID_BODY", 400)))
                .flatMap(body -> inventory.confirm(request.pathVariable("orderId"), body.customerId(), body.commandId()))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> availability(ServerRequest request) {
        return authenticate(request).then(inventory.availability(request.pathVariable("eventId")))
                .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    public Mono<ServerResponse> tickets(ServerRequest request) {
        return authenticate(request).then(Mono.defer(() -> {
            int limit;
            try { limit = Integer.parseInt(request.queryParam("limit").orElse("50")); }
            catch (NumberFormatException error) { return Mono.error(new InventoryException("INVALID_PAGE_SIZE", 400)); }
            return inventory.tickets(request.pathVariable("eventId"), limit, request.queryParam("cursor").orElse(null));
        })).flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<Void> authenticate(ServerRequest request) {
        boolean authenticated;
        if ("local".equals(authMode) && Set.of(profiles.split(",")).contains("local")) {
            authenticated = Set.of("Bearer local-customer-1", "Bearer local-customer-2", "Bearer local-admin")
                    .contains(request.headers().firstHeader("Authorization") == null ? "" : request.headers().firstHeader("Authorization"));
        } else {
            String subject = request.headers().firstHeader("X-Authenticated-Subject");
            authenticated = "trusted-gateway".equals(authMode) && subject != null && !subject.isBlank();
        }
        return authenticated ? Mono.empty() : Mono.error(new InventoryException("UNAUTHENTICATED", 401));
    }

    public record Confirmation(String customerId, String commandId) { }
}
