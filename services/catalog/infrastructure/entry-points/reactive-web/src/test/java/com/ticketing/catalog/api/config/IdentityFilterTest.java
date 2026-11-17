package com.ticketing.catalog.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ticketing.catalog.api.Identity;
import com.ticketing.catalog.model.DomainFailure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class IdentityFilterTest {
    @Test
    void localIdentityRequiresLocalProfileAndRejectsInvalidModes() {
        assertThrows(IllegalStateException.class, () -> new IdentityFilter("local", "prod"));
        assertThrows(IllegalStateException.class, () -> new IdentityFilter("unknown", "local"));
        IdentityFilter filter = new IdentityFilter("local", "dev,local");
        for (String bearer : new String[] { "Bearer local-admin", "Bearer local-customer-1",
            "Bearer local-customer-2" }) {
            var exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/api/v1/events").header("Authorization", bearer));
            StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();
            Identity identity = exchange.getAttribute("identity");
            assertNotNull(identity);
            assertEquals(bearer.substring(7), identity.subject());
            assertEquals(bearer.endsWith("admin"), identity.admin());
        }
        var invalid = MockServerWebExchange
            .from(MockServerHttpRequest.get("/api/v1/events").header("Authorization", "Bearer forged"));
        StepVerifier.create(filter.filter(invalid, ignored -> Mono.empty())).expectError(DomainFailure.class)
            .verify();
        var health = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health"));
        StepVerifier.create(filter.filter(health, ignored -> Mono.empty())).verifyComplete();
        assertNull(health.getAttribute("identity"));
    }

    @Test
    void trustedGatewayValidatesSubjectAndParsesGroups() {
        IdentityFilter filter = new IdentityFilter("trusted-gateway", "prod");
        for (String subject : new String[] { "", " ", "x".repeat(129) }) {
            var exchange = MockServerWebExchange
                .from(MockServerHttpRequest.get("/api/v1/events").header("X-Authenticated-Subject", subject));
            StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty()))
                .expectError(DomainFailure.class).verify();
        }
        var missing = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));
        StepVerifier.create(filter.filter(missing, ignored -> Mono.empty())).expectError(DomainFailure.class)
            .verify();
        for (String groups : new String[] { "[\"admin\", \"customer\"]", "customer", "" }) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events")
                .header("X-Authenticated-Subject", "subject").header("X-Authenticated-Groups", groups));
            StepVerifier.create(filter.filter(exchange, ignored -> Mono.empty())).verifyComplete();
            Identity identity = exchange.getAttribute("identity");
            assertNotNull(identity);
            assertEquals(groups.contains("admin"), identity.admin());
        }
        var noGroups = MockServerWebExchange
            .from(MockServerHttpRequest.get("/api/v1/events").header("X-Authenticated-Subject", "subject"));
        StepVerifier.create(filter.filter(noGroups, ignored -> Mono.empty())).verifyComplete();
        assertFalse(((Identity) noGroups.getAttribute("identity")).admin());
        new IdentityFilter("trusted-gateway", "local");
    }

    @Test
    void missingIdentityCannotAuthorizeAnAdmin() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));
        ServerRequest request = ServerRequest.create(exchange,
            HandlerStrategies.withDefaults().messageReaders());
        assertThrows(DomainFailure.class, () -> Identity.from(request));
    }
}
