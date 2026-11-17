package com.ticketing.catalog.api.config;

import static org.mockito.Mockito.when;

import com.ticketing.catalog.api.RequestHandler;
import com.ticketing.catalog.api.Routes;
import com.ticketing.catalog.api.mapper.EventResponseMapperImpl;
import com.ticketing.catalog.model.EventPage;
import com.ticketing.catalog.usecase.CatalogUseCase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.util.List;

@ContextConfiguration(classes = { Routes.class, RequestHandler.class, EventResponseMapperImpl.class })
@WebFluxTest
@Import({ CorsConfig.class, SecurityHeadersConfig.class })
class ConfigTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CatalogUseCase useCase;

    @Test
    void corsConfigurationShouldAllowOrigins() {
        when(useCase.listDefaultPage(null)).thenReturn(Mono.just(new EventPage(List.of(), null)));
        webTestClient.get().uri("/api/v1/events").exchange().expectStatus().isOk().expectHeader()
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
