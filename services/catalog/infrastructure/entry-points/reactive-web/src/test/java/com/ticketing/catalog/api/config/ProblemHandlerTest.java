package com.ticketing.catalog.api.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketing.catalog.model.DomainFailure;

import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import reactor.test.StepVerifier;

import java.util.List;

class ProblemHandlerTest {
    @Test
    void errorsHaveSafeProblemDetailsAndCorrectStatus() {
        for (Throwable failure : List.of(new DomainFailure(409, "CONFLICT"), new DecodingException("invalid"),
            new DataBufferLimitException("large"), new IllegalArgumentException("invalid"),
            new ResponseStatusException(HttpStatus.NOT_FOUND),
            new IllegalStateException("private dependency details"))) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/events"));
            StepVerifier.create(new ProblemHandler().handle(exchange, failure)).verifyComplete();
            int expected = failure instanceof DomainFailure ? 409
                : failure instanceof ResponseStatusException ? 404
                    : failure.getClass() == IllegalStateException.class ? 503 : 400;
            assertEquals(expected, exchange.getResponse().getStatusCode().value());
            String body = exchange.getResponse().getBodyAsString().block();
            assertNotNull(body);
            assertTrue(body.contains("traceId"));
            assertFalse(body.contains("private dependency details"));
        }
    }

    @Test
    void committedResponsePropagatesOriginalFailure() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
        exchange.getResponse().setComplete().block();
        RuntimeException failure = new RuntimeException("original");
        StepVerifier.create(new ProblemHandler().handle(exchange, failure))
            .expectErrorMatches(error -> error == failure).verify();
    }
}
