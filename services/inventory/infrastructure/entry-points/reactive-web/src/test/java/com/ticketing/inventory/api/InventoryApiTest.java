package com.ticketing.inventory.api;

import com.ticketing.inventory.model.*;
import com.ticketing.inventory.usecase.InventoryUseCase;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import static org.mockito.Mockito.*;

class InventoryApiTest {
    private InventoryUseCase inventory;
    private WebTestClient http;

    @BeforeEach void setup() {
        inventory = mock(InventoryUseCase.class);
        http = WebTestClient.bindToRouterFunction(new RouterRest().routerFunction(new Handler(inventory, "local", "local"))).build();
    }

    @Test void authenticatesPublicEndpointsAndReturnsSnapshot() {
        when(inventory.availability("e1")).thenReturn(Mono.just(new Availability("e1", 10, 8, 2, 0, 0, 0)));
        http.get().uri("/api/v1/events/e1/availability").exchange().expectStatus().isUnauthorized();
        http.get().uri("/api/v1/events/e1/availability").header("Authorization", "Bearer local-customer-1")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.available").isEqualTo(8);
        var trusted = WebTestClient.bindToRouterFunction(new RouterRest().routerFunction(new Handler(inventory, "trusted-gateway", "aws"))).build();
        trusted.get().uri("/api/v1/events/e1/availability").header("X-Authenticated-Subject", "customer")
                .exchange().expectStatus().isOk();
        trusted.get().uri("/api/v1/events/e1/availability").header("Authorization", "Bearer local-admin")
                .exchange().expectStatus().isUnauthorized();
        var badMode = WebTestClient.bindToRouterFunction(new RouterRest().routerFunction(new Handler(inventory, "local", "aws"))).build();
        badMode.get().uri("/api/v1/events/e1/availability").header("Authorization", "Bearer local-admin")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test void paginatesAndRejectsInvalidLimits() {
        when(inventory.tickets("e1", 50, null)).thenReturn(Mono.just(new TicketPage(List.of(new Ticket("e1", "1", TicketStatus.AVAILABLE)), "2")));
        http.get().uri("/api/v1/events/e1/tickets").header("Authorization", "Bearer local-admin")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.nextCursor").isEqualTo("2");
        http.get().uri("/api/v1/events/e1/tickets?limit=invalid").header("Authorization", "Bearer local-customer-2")
                .exchange().expectStatus().isBadRequest();
    }

    @Test void reservesQueriesAndConfirmsThroughInternalRoutes() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var request = new ReservationRequest("o1", "e1", "c1", List.of("1"), now, now.plusSeconds(600), null, null, null);
        var reserved = Reservation.accepted(request);
        when(inventory.reserve(any())).thenReturn(Mono.just(reserved));
        when(inventory.get("o1")).thenReturn(Mono.just(reserved));
        when(inventory.confirm("o1", "c1", "confirm:o1")).thenReturn(Mono.just(reserved));
        http.put().uri("/internal/reservations/o1").bodyValue(request).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("RESERVED");
        http.get().uri("/internal/reservations/o1").exchange().expectStatus().isOk();
        http.post().uri("/internal/reservations/o1/confirm").bodyValue(new Handler.Confirmation("c1", "confirm:o1"))
                .exchange().expectStatus().isOk();
        http.put().uri("/internal/reservations/o1").exchange().expectStatus().isBadRequest();
        http.post().uri("/internal/reservations/o1/confirm").exchange().expectStatus().isBadRequest();
    }

    @Test void normalizesBusinessAndDependencyErrors() {
        when(inventory.get("missing")).thenReturn(Mono.error(new InventoryException("RESERVATION_NOT_FOUND", 404)));
        http.get().uri("/internal/reservations/missing").exchange().expectStatus().isNotFound()
                .expectHeader().contentType("application/problem+json").expectBody().jsonPath("$.traceId").exists();
        when(inventory.get("unavailable")).thenReturn(Mono.error(new IllegalStateException("Internal sensitive detail")));
        http.get().uri("/internal/reservations/unavailable").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
        http.put().uri("/internal/reservations/o1").header("Content-Type", "application/json").bodyValue("{")
                .exchange().expectStatus().isBadRequest();
    }
}
