package com.ticketing.orders.consumer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import com.ticketing.orders.consumer.config.RestConsumerConfig;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;
import com.ticketing.orders.model.*;
import com.ticketing.orders.model.Order;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RestConsumerTest {
    MockWebServer server; RestConsumer consumer;
    AnnotationConfigApplicationContext context; ConnectionProvider connections;
    @BeforeAll static void initializeHttpRuntime() {
        reactor.netty.http.client.HttpClient.create().warmup().block(java.time.Duration.ofSeconds(20));
    }
    @BeforeEach void setUp() throws Exception {
        server=new MockWebServer(); server.start();
        context=new AnnotationConfigApplicationContext();
        context.registerBean(WebClient.Builder.class, WebClient::builder);
        context.registerBean(RestConsumerConfig.class, () -> new RestConsumerConfig(server.url("/").toString(), 2000));
        context.refresh();
        connections=context.getBean(ConnectionProvider.class);
        consumer=new RestConsumer(context.getBean(WebClient.class));
    }
    @AfterEach void tearDown() throws Exception {
        context.close(); server.shutdown();
        assertTrue(connections.isDisposed(), "Spring must release its owned HTTP connection pool");
    }
    String body() { return "{\"orderId\":\"o\",\"eventId\":\"e\",\"customerId\":\"c\",\"status\":\"RESERVED\",\"resolution\":\"\",\"inventoryVersion\":1,\"requestedTicketIds\":[\"1\"],\"activeTicketIds\":[\"1\"],\"expiresAt\":\"2026-11-16T00:10:00Z\",\"additionalField\":true}"; }
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
}
