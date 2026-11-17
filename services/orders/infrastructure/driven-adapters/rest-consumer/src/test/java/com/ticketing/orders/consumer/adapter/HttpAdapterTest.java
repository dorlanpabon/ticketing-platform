package com.ticketing.orders.consumer.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketing.orders.consumer.config.HttpClientConfig;
import com.ticketing.orders.consumer.config.HttpClientProperties;
import com.ticketing.orders.consumer.dto.response.ReservationResponse;
import com.ticketing.orders.consumer.mapper.ReservationMapper;
import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.Reservation;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.netty.http.HttpResources;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;

class HttpAdapterTest {
    MockWebServer server;
    HttpAdapter consumer;
    AnnotationConfigApplicationContext context;
    ConnectionProvider connections;

    @BeforeAll
    @Timeout(20)
    static void initializeHttpRuntime() throws Exception {
        ConnectionProvider warmupConnections;
        try (MockWebServer warmupServer = new MockWebServer()) {
            warmupServer.start();
            try (var warmupContext = configuredContext(warmupServer.url("/").toString())) {
                warmupConnections = warmupContext.getBean(ConnectionProvider.class);
                warmupServer.enqueue(
                    new MockResponse().setHeader("Content-Type", "application/json").setBody(body()));
                ReservationResponse decoded = warmupContext.getBean(WebClient.class).get().uri("/warmup")
                    .retrieve().bodyToMono(ReservationResponse.class).block(Duration.ofSeconds(20));
                assertNotNull(decoded);
                assertEquals("RESERVED", decoded.status());
            }
        }
        assertTrue(warmupConnections.isDisposed(), "Warmup must release its separate HTTP pool");
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        context = configuredContext(server.url("/").toString());
        connections = context.getBean(ConnectionProvider.class);
        consumer = context.getBean(HttpAdapter.class);
    }

    private static AnnotationConfigApplicationContext configuredContext(String baseUrl) {
        var configured = new AnnotationConfigApplicationContext();
        configured.registerBean(WebClient.Builder.class, WebClient::builder);
        configured.register(HttpClientConfig.class, HttpAdapter.class);
        configured.scan(ReservationMapper.class.getPackageName());
        TestPropertyValues
            .of("adapter.rest-consumer.base-url=" + baseUrl, "adapter.rest-consumer.transport-timeout=2s",
                "adapter.rest-consumer.request-timeout=2s", "adapter.rest-consumer.min-pool-size=500",
                "adapter.rest-consumer.max-idle-time=15s", "adapter.rest-consumer.max-life-time=30s",
                "adapter.rest-consumer.eviction-interval=15s", "adapter.rest-consumer.dns-cache-ttl=30s")
            .applyTo(configured);
        try {
            configured.refresh();
            return configured;
        } catch (RuntimeException error) {
            configured.close();
            throw error;
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        context.close();
        server.shutdown();
        assertTrue(connections.isDisposed(), "Spring must release its owned HTTP connection pool");
    }

    static String body() {
        return "{\"orderId\":\"o\",\"eventId\":\"e\",\"customerId\":\"c\",\"status\":\"RESERVED\",\"resolution\":\"\",\"inventoryVersion\":1,\"requestedTicketIds\":[\"1\"],\"activeTicketIds\":[\"1\"],\"expiresAt\":\"2026-11-16T00:10:00Z\",\"additionalField\":true}";
    }

    @Test
    void readsAuthorityAndToleratesAdditiveContractFields() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body()));
        var reservation = new Reservation("o", "e", "c", "RESERVED", "", 1, List.of("1"), List.of("1"),
            Instant.parse("2026-11-16T00:10:00Z"));
        StepVerifier.create(consumer.find("o")).expectNext(reservation).verifyComplete();
        assertEquals("/internal/reservations/o", server.takeRequest().getPath());
    }

    @Test
    void ownedPoolPreservesSharedCapacityAndReusesLiveConnections() throws Exception {
        assertEquals(HttpResources.get().maxConnections(), connections.maxConnections());
        for (int request = 0; request < 2; request++) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body()));
            StepVerifier.create(consumer.find("o")).expectNextCount(1).verifyComplete();
            assertEquals(request, server.takeRequest().getSequenceNumber());
        }
        assertFalse(connections.isDisposed());
    }

    @Test
    void absentReservationIsEmptyButAuthorityOutageIsNotHidden() {
        server.enqueue(new MockResponse().setResponseCode(404));
        StepVerifier.create(consumer.find("o")).verifyComplete();
        server.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(consumer.find("o")).expectError(DomainFailure.class).verify();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("invalid"));
        StepVerifier.create(consumer.find("o")).expectError(DomainFailure.class).verify();
        server.enqueue(
            new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"orderId\":\"o\"}"));
        StepVerifier.create(consumer.find("o")).expectErrorSatisfies(error -> {
            var failure = assertInstanceOf(DomainFailure.class, error);
            assertEquals(503, failure.status());
            assertEquals("INVENTORY_UNAVAILABLE", failure.code());
        }).verify();
    }

    @Test
    void reservationUsesImmutableDeadlineAndRequestedTickets() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body()));
        Instant now = Instant.parse("2026-11-16T00:00:00Z");
        Order order = new Order("o", "e", "c", List.of("1"), List.of(), "AVAILABLE", "RESERVING", "", 0, now,
            now.plusSeconds(600), "PURCHASE", "c", "");
        StepVerifier.create(consumer.reserve(order)).expectNextCount(1).verifyComplete();
        var request = server.takeRequest();
        assertEquals("PUT", request.getMethod());
        var payload = JsonMapper.builder().build().readTree(request.getBody().readUtf8());
        assertEquals("2026-11-16T00:10:00Z", payload.path("expiresAt").asString());
        assertEquals("1", payload.path("ticketIds").path(0).asString());
    }

    @Test
    void unansweredAuthorityReadFailsExactlyAtTwoSeconds() {
        var unavailable = new HttpAdapter(
            WebClient.builder().exchangeFunction(request -> Mono.never()).build(),
            context.getBean(HttpClientProperties.class), context.getBean(ReservationMapper.class));
        StepVerifier.withVirtualTime(() -> unavailable.find("o")).expectSubscription()
            .expectNoEvent(Duration.ofMillis(1999)).thenAwait(Duration.ofMillis(1))
            .expectErrorSatisfies(error -> {
                var failure = assertInstanceOf(DomainFailure.class, error);
                assertEquals(503, failure.status());
                assertEquals("INVENTORY_UNAVAILABLE", failure.code());
            }).verify(Duration.ofSeconds(5));
    }

    @Test
    void unansweredReservationFailsExactlyAtTwoSeconds() {
        var unavailable = new HttpAdapter(
            WebClient.builder().exchangeFunction(request -> Mono.never()).build(),
            context.getBean(HttpClientProperties.class), context.getBean(ReservationMapper.class));
        Instant now = Instant.parse("2026-11-16T00:00:00Z");
        Order order = new Order("o", "e", "c", List.of("1"), List.of(), "AVAILABLE", "RESERVING", "", 0, now,
            now.plusSeconds(600), "PURCHASE", "c", "");
        StepVerifier.withVirtualTime(() -> unavailable.reserve(order)).expectSubscription()
            .expectNoEvent(Duration.ofMillis(1999)).thenAwait(Duration.ofMillis(1))
            .expectError(TimeoutException.class).verify(Duration.ofSeconds(5));
    }
}
