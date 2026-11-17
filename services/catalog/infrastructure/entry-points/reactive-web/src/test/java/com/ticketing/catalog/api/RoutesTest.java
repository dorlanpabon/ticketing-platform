package com.ticketing.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.catalog.api.config.IdentityFilter;
import com.ticketing.catalog.api.config.ProblemHandler;
import com.ticketing.catalog.api.dto.request.CreateEventRequest;
import com.ticketing.catalog.api.mapper.EventResponseMapperImpl;
import com.ticketing.catalog.model.Event;
import com.ticketing.catalog.model.EventPage;
import com.ticketing.catalog.usecase.CatalogUseCase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

@ContextConfiguration(classes = { Routes.class, RequestHandler.class, IdentityFilter.class,
    ProblemHandler.class, EventResponseMapperImpl.class })
@WebFluxTest(properties = { "AUTH_MODE=local", "spring.profiles.active=local" })
class RoutesTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private CatalogUseCase useCase;
    private final Event event = new Event("event-1", "Concert", "Arena",
        Instant.parse("2030-01-01T00:00:00Z"), 10, "PROVISIONING");

    @Test
    void testListenGETUseCase() {
        when(useCase.find("event-1")).thenReturn(Mono.just(event));
        webTestClient.get().uri("/api/v1/events/event-1").header("Authorization", "Bearer local-customer-1")
            .accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk().expectBody().json("""
                {
                    "eventId": "event-1",
                    "name": "Concert",
                    "venue": "Arena",
                    "startsAt": "2030-01-01T00:00:00Z",
                    "capacity": 10,
                    "status": "PROVISIONING"
                }
                """, JsonCompareMode.STRICT);
    }

    @Test
    void listsDefaultPageWhenLimitIsOmitted() {
        when(useCase.listDefaultPage(null)).thenReturn(Mono.just(new EventPage(List.of(event), "next-page")));
        webTestClient.get().uri("/api/v1/events").header("Authorization", "Bearer local-customer-1")
            .accept(MediaType.APPLICATION_JSON).exchange().expectStatus().isOk().expectBody().json("""
                {
                    "items": [{
                        "eventId": "event-1",
                        "name": "Concert",
                        "venue": "Arena",
                        "startsAt": "2030-01-01T00:00:00Z",
                        "capacity": 10,
                        "status": "PROVISIONING"
                    }],
                    "cursor": "next-page"
                }
                """, JsonCompareMode.STRICT);
    }

    @Test
    void forwardsExplicitLimitAndCursor() {
        when(useCase.list(7, "next")).thenReturn(Mono.just(new EventPage(List.of(), null)));

        webTestClient.get().uri("/api/v1/events?limit=7&cursor=next")
            .header("Authorization", "Bearer local-customer-1").exchange().expectStatus().isOk().expectBody()
            .json("""
                {"items": [], "cursor": null}
                """, JsonCompareMode.STRICT);
    }

    @Test
    void testListenPOSTUseCase() {
        when(useCase.create(eq("local-admin"), eq("create-001"), eq("Concert"), eq("Arena"), any(), eq(10)))
            .thenReturn(Mono.just(event));
        webTestClient.post().uri("/api/v1/events").header("Authorization", "Bearer local-admin")
            .header("Idempotency-Key", "create-001").accept(MediaType.APPLICATION_JSON)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new CreateEventRequest("Concert", "Arena", event.startsAt(), 10)).exchange()
            .expectStatus().isAccepted().expectHeader().valueEquals("Location", "/api/v1/events/event-1")
            .expectBody().json("""
                {
                    "eventId": "event-1",
                    "name": "Concert",
                    "venue": "Arena",
                    "startsAt": "2030-01-01T00:00:00Z",
                    "capacity": 10,
                    "status": "PROVISIONING"
                }
                """, JsonCompareMode.STRICT);
    }

    @Test
    void deniesAnonymousAndCustomerWrites() {
        webTestClient.get().uri("/api/v1/events").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/events").header("Authorization", "Bearer local-customer-2")
            .exchange().expectStatus().isForbidden();
    }

    @Test
    void rejectsInvalidQueryAndJson() {
        webTestClient.get().uri("/api/v1/events?limit=abc").header("Authorization", "Bearer local-admin")
            .exchange().expectStatus().isBadRequest();
        webTestClient.post().uri("/api/v1/events").header("Authorization", "Bearer local-admin")
            .contentType(MediaType.APPLICATION_JSON).bodyValue("{").exchange().expectStatus().isBadRequest();
    }

    @Test
    void rejectsMissingCreationBodyBeforeCallingTheUseCase() {
        webTestClient.post().uri("/api/v1/events").header("Authorization", "Bearer local-admin")
            .header("Idempotency-Key", "create-empty").contentType(MediaType.APPLICATION_JSON).exchange()
            .expectStatus().isBadRequest().expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_BODY");
        verifyNoInteractions(useCase);
    }
}
