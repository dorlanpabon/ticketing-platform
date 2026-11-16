package com.ticketing.orders.api;

import com.ticketing.orders.api.dto.request.ComplimentaryOrderRequest;
import com.ticketing.orders.api.dto.request.PurchaseRequest;
import com.ticketing.orders.api.mapper.OrderResponseMapperImpl;
import static org.mockito.Mockito.when;

import com.ticketing.orders.api.config.IdentityFilter;
import com.ticketing.orders.api.config.ProblemHandler;
import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.usecase.OrdersUseCase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@ContextConfiguration(classes = { Routes.class, RequestHandler.class, OrderResponseMapperImpl.class })
@WebFluxTest(properties = { "spring.profiles.active=local", "AUTH_MODE=local" })
@Import({ IdentityFilter.class, ProblemHandler.class })
class RoutesTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private OrdersUseCase useCase;

    private Order order() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        return new Order("order", "event", "local-customer-1", List.of("1"), List.of(), "AVAILABLE",
            "RESERVING", "", 0, now, now.plusSeconds(600), "PURCHASE", "local-customer-1", "");
    }

    @Test
    void testListenGETUseCase() {
        when(useCase.get("local-customer-1", "order")).thenReturn(Mono.just(order()));
        webTestClient.get().uri("/api/v1/orders/order").header("Authorization", "Bearer local-customer-1")
            .accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.orderId").isEqualTo("order").jsonPath("$.eventId").isEqualTo("event")
            .jsonPath("$.customerId").isEqualTo("local-customer-1").jsonPath("$.requestedTicketIds[0]")
            .isEqualTo("1").jsonPath("$.activeTicketIds").isEmpty().jsonPath("$.status")
            .isEqualTo("AVAILABLE").jsonPath("$.workflowStatus").isEqualTo("RESERVING")
            .jsonPath("$.resolution").isEqualTo("").jsonPath("$.inventoryVersion").isEqualTo(0)
            .jsonPath("$.requestedAt").isEqualTo("2026-11-16T12:00:00Z").jsonPath("$.expiresAt")
            .isEqualTo("2026-11-16T12:10:00Z").jsonPath("$.type").isEqualTo("PURCHASE").jsonPath("$.actorId")
            .isEqualTo("local-customer-1").jsonPath("$.reason").isEqualTo("");
    }

    @Test
    void testListenGETOtherUseCase() {
        when(useCase.get("local-customer-2", "order"))
            .thenReturn(Mono.error(new DomainFailure(404, "ORDER_NOT_FOUND")));
        webTestClient.get().uri("/api/v1/orders/order").header("Authorization", "Bearer local-customer-2")
            .accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isNotFound().expectBody()
            .jsonPath("$.code").isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void testListenPOSTUseCase() {
        when(useCase.create("local-customer-1", "request-0001", "event", List.of("1"), "local-customer-1", "",
            false)).thenReturn(Mono.just(order()));
        webTestClient.post().uri("/api/v1/orders").header("Authorization", "Bearer local-customer-1")
            .header("Idempotency-Key", "request-0001").accept(MediaType.APPLICATION_JSON)
            .bodyValue(new PurchaseRequest("event", List.of("1"))).exchange().expectStatus().isAccepted()
            .expectHeader().valueEquals("Location", "/api/v1/orders/order").expectBody()
            .jsonPath("$.workflowStatus").isEqualTo("RESERVING");
    }

    @Test
    void confirmsAndRequiresAdminForCourtesy() {
        when(useCase.confirm("local-customer-1", "order", "confirm-0001")).thenReturn(Mono.just(order()));
        webTestClient.post().uri("/api/v1/orders/order/confirm")
            .header("Authorization", "Bearer local-customer-1").header("Idempotency-Key", "confirm-0001")
            .exchange().expectStatus().isAccepted();
        var body = new ComplimentaryOrderRequest("event", List.of("1"), "guest", "Speaker");
        webTestClient.post().uri("/api/v1/admin/complimentary-orders")
            .header("Authorization", "Bearer local-customer-1").header("X-Authenticated-Groups", "admin")
            .bodyValue(body).exchange().expectStatus().isForbidden();
        when(useCase.create("guest", "courtesy-001", "event", List.of("1"), "local-admin", "Speaker", true))
            .thenReturn(Mono.just(order()));
        webTestClient.post().uri("/api/v1/admin/complimentary-orders")
            .header("Authorization", "Bearer local-admin").header("Idempotency-Key", "courtesy-001")
            .bodyValue(body).exchange().expectStatus().isAccepted();
    }

    @Test
    void rejectsMissingIdentityBodiesAndMalformedJson() {
        webTestClient.get().uri("/api/v1/orders/order").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/orders/order").header("Authorization", "Bearer forged").exchange()
            .expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/orders").header("Authorization", "Bearer local-customer-1")
            .exchange().expectStatus().isBadRequest();
        webTestClient.post().uri("/api/v1/admin/complimentary-orders")
            .header("Authorization", "Bearer local-admin").exchange().expectStatus().isBadRequest();
        webTestClient.post().uri("/api/v1/orders").header("Authorization", "Bearer local-customer-1")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{").exchange().expectStatus().isBadRequest();
        when(useCase.get("local-customer-1", "offline"))
            .thenReturn(Mono.error(new IllegalStateException("sensitive")));
        webTestClient.get().uri("/api/v1/orders/offline").header("Authorization", "Bearer local-customer-1")
            .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.title")
            .isEqualTo("DEPENDENCY_UNAVAILABLE");
    }
}
