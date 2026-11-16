package com.ticketing.inventory.api.config;

import com.ticketing.inventory.api.RequestHandler;
import com.ticketing.inventory.api.Routes;
import com.ticketing.inventory.api.mapper.HttpMapperImpl;
import com.ticketing.inventory.model.Availability;
import com.ticketing.inventory.usecase.InventoryUseCase;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

@ContextConfiguration(classes = { Routes.class, RequestHandler.class })
@WebFluxTest(properties = "inventory.default-page-size=50")
@Import({ CorsConfig.class, SecurityHeadersConfig.class, HttpMapperImpl.class })
class ConfigTest {

    @MockitoBean
    private InventoryUseCase inventory;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void corsConfigurationShouldAllowOrigins() {
        Mockito.when(inventory.availability("e1"))
            .thenReturn(Mono.just(new Availability("e1", 1, 1, 0, 0, 0, 0)));
        webTestClient.get().uri("/api/v1/events/e1/availability")
            .header("X-Authenticated-Subject", "customer").exchange().expectStatus().isOk().expectHeader()
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
