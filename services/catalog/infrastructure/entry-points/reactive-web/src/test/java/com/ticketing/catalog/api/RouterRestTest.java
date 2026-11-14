package com.ticketing.catalog.api;

import com.ticketing.catalog.model.Event;
import com.ticketing.catalog.model.EventPage;
import com.ticketing.catalog.usecase.CatalogUseCase;
import com.ticketing.catalog.api.config.IdentityFilter;
import com.ticketing.catalog.api.config.ProblemHandler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;

@ContextConfiguration(classes = {RouterRest.class, Handler.class, IdentityFilter.class, ProblemHandler.class})
@WebFluxTest(properties = {"AUTH_MODE=local", "spring.profiles.active=local"})
class RouterRestTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean private CatalogUseCase useCase;
    private final Event event = new Event("event-1", "Concert", "Arena", Instant.parse("2030-01-01T00:00:00Z"), 10, "PROVISIONING");

    @Test
    void testListenGETUseCase() {
        when(useCase.find("event-1")).thenReturn(Mono.just(event));
        webTestClient.get()
                .uri("/api/v1/events/event-1")
                .header("Authorization", "Bearer local-customer-1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.eventId").isEqualTo("event-1");
    }

    @Test
    void testListenGETOtherUseCase() {
        when(useCase.list(50, null)).thenReturn(Mono.just(new EventPage(List.of(event), null)));
        webTestClient.get()
                .uri("/api/v1/events")
                .header("Authorization", "Bearer local-customer-1")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].eventId").isEqualTo("event-1");
    }

    @Test
    void testListenPOSTUseCase() {
        when(useCase.create(eq("local-admin"), eq("create-001"), eq("Concert"), eq("Arena"), any(), eq(10))).thenReturn(Mono.just(event));
        webTestClient.post()
                .uri("/api/v1/events")
                .header("Authorization", "Bearer local-admin")
                .header("Idempotency-Key", "create-001")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new Handler.CreateEvent("Concert", "Arena", event.startsAt(), 10))
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", "/api/v1/events/event-1")
                .expectBody().jsonPath("$.status").isEqualTo("PROVISIONING");
    }

    @Test void deniesAnonymousAndCustomerWrites() {
        webTestClient.get().uri("/api/v1/events").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/events").header("Authorization", "Bearer local-customer-2")
                .exchange().expectStatus().isForbidden();
    }

    @Test void rejectsInvalidQueryAndJson() {
        webTestClient.get().uri("/api/v1/events?limit=abc").header("Authorization", "Bearer local-admin")
                .exchange().expectStatus().isBadRequest();
        webTestClient.post().uri("/api/v1/events").header("Authorization", "Bearer local-admin")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{")
                .exchange().expectStatus().isBadRequest();
    }
}
