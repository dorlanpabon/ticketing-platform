package com.ticketing.orders.api.config;

import com.ticketing.orders.api.Handler;
import com.ticketing.orders.api.RouterRest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

@ContextConfiguration(classes = {RouterRest.class, Handler.class})
@WebFluxTest(properties = {"spring.profiles.active=local", "AUTH_MODE=local", "cors.allowed-origins="})
@Import({CorsConfig.class, SecurityHeadersConfig.class, IdentityFilter.class, ProblemHandler.class})
class ConfigTest {

    @Autowired
    private WebTestClient webTestClient;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.ticketing.orders.usecase.OrdersUseCase useCase;

    @Test
    void corsConfigurationShouldAllowOrigins() {
        var now = java.time.Instant.parse("2026-11-16T12:00:00Z");
        var order = new com.ticketing.orders.model.Order("order", "event", "local-customer-1", java.util.List.of("1"), java.util.List.of(), "AVAILABLE", "RESERVING", "", 0,
                now, now.plusSeconds(600), "PURCHASE", "local-customer-1", "");
        org.mockito.Mockito.when(useCase.get("local-customer-1", "order")).thenReturn(reactor.core.publisher.Mono.just(order));
        webTestClient.get()
                .uri("/api/v1/orders/order")
                .header("Authorization", "Bearer local-customer-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Security-Policy",
                        "default-src 'self'; frame-ancestors 'self'; form-action 'self'")
                .expectHeader().valueEquals("Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().doesNotExist("Server")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().valueEquals("Pragma", "no-cache")
                .expectHeader().valueEquals("Referrer-Policy", "strict-origin-when-cross-origin");
    }

}
