package com.ticketing.orders.consumer;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import com.ticketing.orders.model.*;
import com.ticketing.orders.model.Order;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class RestConsumerTest {
    MockWebServer server; RestConsumer consumer;
    @BeforeAll static void initializeHttpRuntime() {
        reactor.netty.http.client.HttpClient.create().warmup().block(java.time.Duration.ofSeconds(20));
    }
    @BeforeEach void setUp() throws Exception { server=new MockWebServer(); server.start(); consumer=new RestConsumer(WebClient.builder().baseUrl(server.url("/").toString()).build()); }
    @AfterEach void tearDown() throws Exception { server.shutdown(); }
    String body() { return "{\"orderId\":\"o\",\"eventId\":\"e\",\"customerId\":\"c\",\"status\":\"RESERVED\",\"resolution\":\"\",\"inventoryVersion\":1,\"requestedTicketIds\":[\"1\"],\"activeTicketIds\":[\"1\"],\"expiresAt\":\"2026-11-16T00:10:00Z\",\"additionalField\":true}"; }
    @Test void readsAuthorityAndToleratesAdditiveContractFields() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body()));
        StepVerifier.create(consumer.find("o")).assertNext(state -> assertEquals("RESERVED",state.status())).verifyComplete();
        assertEquals("/internal/reservations/o",server.takeRequest().getPath());
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
