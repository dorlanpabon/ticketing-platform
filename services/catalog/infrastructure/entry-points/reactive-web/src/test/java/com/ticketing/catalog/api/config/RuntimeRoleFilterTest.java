package com.ticketing.catalog.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeRoleFilterTest {
    @Test void workersRejectBusinessRoutesBeforeTheyReachHandlers() {
        for (String path : new String[]{"/api", "/api/v1/events", "/internal", "/internal/inventory", "/api%2Fv1/events", "/"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            AtomicBoolean called = new AtomicBoolean();
            StepVerifier.create(new RuntimeRoleFilter(false).filter(exchange, ignored -> {
                called.set(true);
                return Mono.empty();
            })).verifyComplete();
            assertFalse(called.get());
            assertEquals(HttpStatus.NOT_FOUND, exchange.getResponse().getStatusCode());
        }
    }

    @Test void workerProbesAndApiRoleReachTheExistingFilters() {
        for (boolean enabled : new boolean[]{false, true}) {
            String[] paths = enabled ? new String[]{"/api/v1/events", "/internal/inventory"}
                    : new String[]{"/actuator/health", "/actuator/health/readiness", "/actuator/prometheus"};
            for (String path : paths) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                AtomicBoolean called = new AtomicBoolean();
                StepVerifier.create(new RuntimeRoleFilter(enabled).filter(exchange, ignored -> {
                    called.set(true);
                    return Mono.empty();
                })).verifyComplete();
                assertTrue(called.get());
            }
        }
    }
}
