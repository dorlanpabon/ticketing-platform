package com.ticketing.orders.api.config;

import com.ticketing.orders.api.RequestHandler;
import com.ticketing.orders.api.Routes;
import com.ticketing.orders.api.mapper.OrderResponseMapperImpl;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.usecase.OrdersUseCase;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@ContextConfiguration(classes = { Routes.class, RequestHandler.class, OrderResponseMapperImpl.class })
@WebFluxTest(properties = { "spring.profiles.active=local", "AUTH_MODE=local", "cors.allowed-origins=" })
@Import({ CorsConfig.class, SecurityHeadersConfig.class, IdentityFilter.class, ProblemHandler.class })
class ConfigTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private OrdersUseCase useCase;

    @Test
    void corsConfigurationShouldAllowOrigins() {
        var now = Instant.parse("2026-11-16T12:00:00Z");
        var order = new Order("order", "event", "local-customer-1", List.of("1"), List.of(), "AVAILABLE",
            "RESERVING", "", 0, now, now.plusSeconds(600), "PURCHASE", "local-customer-1", "");
        Mockito.when(useCase.get("local-customer-1", "order")).thenReturn(Mono.just(order));
        webTestClient.get().uri("/api/v1/orders/order").header("Authorization", "Bearer local-customer-1")
            .exchange().expectStatus().isOk().expectHeader()
            .valueEquals("Content-Security-Policy",
                "default-src 'self'; frame-ancestors 'self'; form-action 'self'")
            .expectHeader()
            .valueEquals("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff").expectHeader()
            .doesNotExist("Server").expectHeader().valueEquals("Cache-Control", "no-store").expectHeader()
            .valueEquals("Pragma", "no-cache").expectHeader()
            .valueEquals("Referrer-Policy", "strict-origin-when-cross-origin");
    }
}
