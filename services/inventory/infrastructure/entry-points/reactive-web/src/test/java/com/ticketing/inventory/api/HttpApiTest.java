package com.ticketing.inventory.api;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.inventory.api.dto.request.ConfirmationRequest;
import com.ticketing.inventory.api.dto.request.ReserveTicketsRequest;
import com.ticketing.inventory.api.mapper.HttpMapper;
import com.ticketing.inventory.api.mapper.HttpMapperImpl;
import com.ticketing.inventory.model.Availability;
import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.Reservation;
import com.ticketing.inventory.model.ReservationIntent;
import com.ticketing.inventory.model.Ticket;
import com.ticketing.inventory.model.TicketPage;
import com.ticketing.inventory.model.TicketStatus;
import com.ticketing.inventory.usecase.InventoryUseCase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

class HttpApiTest {
    private static final int DEFAULT_PAGE_SIZE = 25;

    private final HttpMapper httpMapper = new HttpMapperImpl();
    private InventoryUseCase inventory;
    private WebTestClient http;

    @BeforeEach
    void setup() {
        inventory = mock(InventoryUseCase.class);
        http = WebTestClient.bindToRouterFunction(new Routes()
            .routerFunction(new RequestHandler(inventory, httpMapper, "local", "local", DEFAULT_PAGE_SIZE)))
            .build();
    }

    @Test
    void authenticatesPublicEndpointsAndReturnsSnapshot() {
        when(inventory.availability("e1")).thenReturn(Mono.just(new Availability("e1", 10, 8, 2, 0, 0, 0)));
        http.get().uri("/api/v1/events/e1/availability").exchange().expectStatus().isUnauthorized();
        http.get().uri("/api/v1/events/e1/availability").header("Authorization", "Bearer local-customer-1")
            .exchange().expectStatus().isOk().expectBody().jsonPath("$.available").isEqualTo(8);
        var trusted = WebTestClient
            .bindToRouterFunction(new Routes().routerFunction(
                new RequestHandler(inventory, httpMapper, "trusted-gateway", "aws", DEFAULT_PAGE_SIZE)))
            .build();
        trusted.get().uri("/api/v1/events/e1/availability").header("X-Authenticated-Subject", "customer")
            .exchange().expectStatus().isOk();
        trusted.get().uri("/api/v1/events/e1/availability").header("Authorization", "Bearer local-admin")
            .exchange().expectStatus().isUnauthorized();
        var badMode = WebTestClient
            .bindToRouterFunction(new Routes()
                .routerFunction(new RequestHandler(inventory, httpMapper, "local", "aws", DEFAULT_PAGE_SIZE)))
            .build();
        badMode.get().uri("/api/v1/events/e1/availability").header("Authorization", "Bearer local-admin")
            .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void paginatesAndRejectsInvalidLimits() {
        when(inventory.tickets("e1", DEFAULT_PAGE_SIZE, null)).thenReturn(
            Mono.just(new TicketPage(List.of(new Ticket("e1", "1", TicketStatus.AVAILABLE)), "2")));
        http.get().uri("/api/v1/events/e1/tickets").header("Authorization", "Bearer local-admin").exchange()
            .expectStatus().isOk().expectBody().jsonPath("$.nextCursor").isEqualTo("2");
        http.get().uri("/api/v1/events/e1/tickets?limit=invalid")
            .header("Authorization", "Bearer local-customer-2").exchange().expectStatus().isBadRequest();
    }

    @Test
    void reservesQueriesAndConfirmsThroughInternalRoutes() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var body = new ReserveTicketsRequest("ignored", "e1", "c1", List.of("1"), now, now.plusSeconds(600),
            null, null, null);
        var expectedRequest = new ReservationIntent("o1", body.eventId(), body.customerId(), body.ticketIds(),
            now, now.plusSeconds(600), "PURCHASE", null, null);
        var reserved = Reservation.accepted(expectedRequest);
        when(inventory.reserve(any())).thenReturn(Mono.just(reserved));
        when(inventory.get("o1")).thenReturn(Mono.just(reserved));
        when(inventory.confirm("o1", "c1", "confirm:o1")).thenReturn(Mono.just(reserved));
        http.put().uri("/internal/reservations/o1").bodyValue(body).exchange().expectStatus().isOk()
            .expectBody().jsonPath("$.status").isEqualTo("RESERVED");
        verify(inventory).reserve(expectedRequest);
        http.get().uri("/internal/reservations/o1").exchange().expectStatus().isOk();
        http.post().uri("/internal/reservations/o1/confirm")
            .bodyValue(new ConfirmationRequest("c1", "confirm:o1")).exchange().expectStatus().isOk();
        http.put().uri("/internal/reservations/o1").exchange().expectStatus().isBadRequest();
        http.post().uri("/internal/reservations/o1/confirm").exchange().expectStatus().isBadRequest();
    }

    @Test
    void normalizesBusinessAndDependencyErrors() {
        when(inventory.get("missing"))
            .thenReturn(Mono.error(new InventoryException("RESERVATION_NOT_FOUND", 404)));
        http.get().uri("/internal/reservations/missing").exchange().expectStatus().isNotFound().expectHeader()
            .contentType("application/problem+json").expectBody().jsonPath("$.traceId").exists();
        when(inventory.get("unavailable"))
            .thenReturn(Mono.error(new IllegalStateException("Internal sensitive detail")));
        http.get().uri("/internal/reservations/unavailable").exchange().expectStatus().isEqualTo(503)
            .expectBody().jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
        http.put().uri("/internal/reservations/o1").header("Content-Type", "application/json").bodyValue("{")
            .exchange().expectStatus().isBadRequest();
        http.put().uri("/internal/reservations/o1").header("Content-Type", "application/json")
            .bodyValue("{\"ticketIds\":[null]}").exchange().expectStatus().isBadRequest();
    }
}
