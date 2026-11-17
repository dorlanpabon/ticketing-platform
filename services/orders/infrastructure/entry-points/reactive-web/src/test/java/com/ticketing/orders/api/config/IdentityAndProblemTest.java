package com.ticketing.orders.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ticketing.orders.api.Identity;
import com.ticketing.orders.model.DomainFailure;

import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

class IdentityAndProblemTest {
    @Test
    void requiresExplicitLocalModeAndRejectsInvalidConfiguration() {
        assertThrows(IllegalStateException.class, () -> new IdentityFilter("local", "aws"));
        assertThrows(IllegalStateException.class, () -> new IdentityFilter("invalid", "local"));
        var filter = new IdentityFilter("trusted-gateway", "aws");
        var health = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        StepVerifier.create(filter.filter(health, exchange -> Mono.empty())).verifyComplete();
        var anonymous = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));
        StepVerifier.create(filter.filter(anonymous, exchange -> Mono.empty()))
            .expectError(DomainFailure.class).verify();
        for (String subject : List.of(" ", "x".repeat(129))) {
            var invalid = MockServerWebExchange
                .from(MockServerHttpRequest.get("/api/v1/orders").header("X-Authenticated-Subject", subject));
            StepVerifier.create(filter.filter(invalid, exchange -> Mono.empty()))
                .expectError(DomainFailure.class).verify();
        }
    }

    @Test
    void mapsOnlyTrustedClaimsAndRequiresAnExactAdminGroup() {
        var filter = new IdentityFilter("trusted-gateway", "aws");
        for (String groups : List.of("[\"admin\",\"customer\"]", "not-admin", "")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders")
                .header("X-Authenticated-Subject", "customer").header("X-Authenticated-Groups", groups));
            StepVerifier.create(filter.filter(exchange, next -> Mono.empty())).verifyComplete();
            var request = ServerRequest.create(exchange, HandlerStrategies.withDefaults().messageReaders());
            var identity = Identity.from(request);
            assertEquals("customer", identity.subject());
            assertEquals(groups.contains("\"admin\""), identity.admin());
        }
        var request = ServerRequest.create(
            MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders")),
            HandlerStrategies.withDefaults().messageReaders());
        assertThrows(DomainFailure.class, () -> Identity.from(request));
        var noGroups = MockServerWebExchange
            .from(MockServerHttpRequest.get("/api/v1/orders").header("X-Authenticated-Subject", "customer"));
        StepVerifier.create(filter.filter(noGroups, next -> Mono.empty())).verifyComplete();
        assertFalse(((Identity) noGroups.getAttributes().get("identity")).admin());
    }

    @Test
    void sanitizesKnownErrorsAndDoesNotRewriteCommittedResponses() {
        var handler = new ProblemHandler();
        for (Throwable error : List.of(new IllegalArgumentException("detail"),
            new DecodingException("detail"), new DataBufferLimitException("detail"))) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));
            StepVerifier.create(handler.handle(exchange, error)).verifyComplete();
            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
        }
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));
        StepVerifier.create(exchange.getResponse().setComplete()).verifyComplete();
        StepVerifier.create(handler.handle(exchange, new IllegalStateException("late")))
            .expectError(IllegalStateException.class).verify();
        var codec = new CodecConfig();
        var configurer = ServerCodecConfigurer.create();
        codec.configureHttpMessageCodecs(configurer);
        assertFalse(configurer.getReaders().isEmpty());
    }
}
