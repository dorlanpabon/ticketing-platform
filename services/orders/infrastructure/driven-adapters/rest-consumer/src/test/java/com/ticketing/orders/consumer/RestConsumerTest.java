package com.ticketing.orders.consumer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import com.ticketing.orders.consumer.config.RestConsumerConfig;
import reactor.netty.resources.ConnectionProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import com.ticketing.orders.model.*;
import com.ticketing.orders.model.Order;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;
class RestConsumerTest {
    MockWebServer server; RestConsumer consumer;
    AnnotationConfigApplicationContext context; ConnectionProvider connections;
    @BeforeAll @Timeout(20) static void initializeHttpRuntime() throws Exception {
        ConnectionProvider warmupConnections;
        try (MockWebServer warmupServer = new MockWebServer()) {
            warmupServer.start();
            try (var warmupContext = configuredContext(warmupServer.url("/").toString())) {
                warmupConnections = warmupContext.getBean(ConnectionProvider.class);
                warmupServer.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body()));
                ObjectResponse decoded = warmupContext.getBean(WebClient.class).get().uri("/warmup")
                        .retrieve().bodyToMono(ObjectResponse.class).block(Duration.ofSeconds(20));
                assertNotNull(decoded);
                assertEquals("RESERVED", decoded.status());
            }
        }
        assertTrue(warmupConnections.isDisposed(), "Warmup must release its separate HTTP pool");
    }
    @BeforeEach void setUp() throws Exception {
        server=new MockWebServer(); server.start();
        context=configuredContext(server.url("/").toString());
        connections=context.getBean(ConnectionProvider.class);
        consumer=new RestConsumer(context.getBean(WebClient.class));
    }
    private static AnnotationConfigApplicationContext configuredContext(String baseUrl) {
        var configured = new AnnotationConfigApplicationContext();
        configured.registerBean(WebClient.Builder.class, WebClient::builder);
        configured.registerBean(RestConsumerConfig.class, () -> new RestConsumerConfig(baseUrl, 2000));
        try {
            configured.refresh();
            return configured;
        } catch (RuntimeException error) {
            configured.close();
            throw error;
        }
    }
    @AfterEach void tearDown() throws Exception {
        context.close(); server.shutdown();
        assertTrue(connections.isDisposed(), "Spring must release its owned HTTP connection pool");
    }
    static String body() { return "{\"orderId\":\"o\",\"eventId\":\"e\",\"customerId\":\"c\",\"status\":\"RESERVED\",\"resolution\":\"\",\"inventoryVersion\":1,\"requestedTicketIds\":[\"1\"],\"activeTicketIds\":[\"1\"],\"expiresAt\":\"2026-11-16T00:10:00Z\",\"additionalField\":true}"; }
    @Test void readsAuthorityAndToleratesAdditiveContractFields() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body()));
        StepVerifier.create(consumer.find("o")).assertNext(state -> assertEquals("RESERVED",state.status())).verifyComplete();
        assertEquals("/internal/reservations/o",server.takeRequest().getPath());
    }
    @Test void ownedPoolPreservesSharedCapacityAndReusesLiveConnections() throws Exception {
        assertEquals(reactor.netty.http.HttpResources.get().maxConnections(), connections.maxConnections());
        for(int request=0;request<2;request++) {
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body()));
            StepVerifier.create(consumer.find("o")).expectNextCount(1).verifyComplete();
            assertEquals(request, server.takeRequest().getSequenceNumber());
        }
        assertFalse(connections.isDisposed());
    }
    @Test void absentReservationIsEmptyButAuthorityOutageIsNotHidden() {
        server.enqueue(new MockResponse().setResponseCode(404)); StepVerifier.create(consumer.find("o")).verifyComplete();
        server.enqueue(new MockResponse().setResponseCode(500)); StepVerifier.create(consumer.find("o")).expectError(DomainFailure.class).verify();
        server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("invalid")); StepVerifier.create(consumer.find("o")).expectError(DomainFailure.class).verify();
    }
    @Test void reservationUsesImmutableDeadlineAndRequestedTickets() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body()));
        Instant now=Instant.parse("2026-11-16T00:00:00Z");
        Order order=new Order("o","e","c",List.of("1"),List.of(),"AVAILABLE","RESERVING","",0,now,now.plusSeconds(600),"PURCHASE","c","");
        StepVerifier.create(consumer.reserve(order)).expectNextCount(1).verifyComplete();
        var request=server.takeRequest(); assertEquals("PUT",request.getMethod()); assertTrue(request.getBody().readUtf8().contains("2026-11-16T00:10:00Z"));
    }
    @Test void unansweredAuthorityReadFailsExactlyAtTwoSeconds() {
        var unavailable = new RestConsumer(WebClient.builder().exchangeFunction(request -> Mono.never()).build());
        StepVerifier.withVirtualTime(() -> unavailable.find("o")).expectSubscription()
                .expectNoEvent(Duration.ofMillis(1999)).thenAwait(Duration.ofMillis(1))
                .expectErrorSatisfies(error -> {
                    var failure = assertInstanceOf(DomainFailure.class, error);
                    assertEquals(503, failure.status());
                    assertEquals("INVENTORY_UNAVAILABLE", failure.code());
                }).verify(Duration.ofSeconds(5));
    }
    @Test void unansweredReservationFailsExactlyAtTwoSeconds() {
        var unavailable = new RestConsumer(WebClient.builder().exchangeFunction(request -> Mono.never()).build());
        Instant now=Instant.parse("2026-11-16T00:00:00Z");
        Order order=new Order("o","e","c",List.of("1"),List.of(),"AVAILABLE","RESERVING","",0,now,now.plusSeconds(600),"PURCHASE","c","");
        StepVerifier.withVirtualTime(() -> unavailable.reserve(order)).expectSubscription()
                .expectNoEvent(Duration.ofMillis(1999)).thenAwait(Duration.ofMillis(1))
                .expectError(TimeoutException.class).verify(Duration.ofSeconds(5));
    }
}
