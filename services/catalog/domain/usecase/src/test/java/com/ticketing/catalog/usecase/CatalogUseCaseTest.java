package com.ticketing.catalog.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.catalog.model.DomainFailure;
import com.ticketing.catalog.model.Envelope;
import com.ticketing.catalog.model.Event;
import com.ticketing.catalog.model.EventPage;
import com.ticketing.catalog.model.Validation;
import com.ticketing.catalog.model.gateways.CatalogPort;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class CatalogUseCaseTest {
    private final CatalogPort port = mock(CatalogPort.class);
    private final Instant now = Instant.parse("2026-11-16T00:00:00Z");
    private final CatalogUseCase useCase = new CatalogUseCase(port, Clock.fixed(now, ZoneOffset.UTC),
        new CatalogLimits(120, 200, 1, 100_000, 1, 100, 50));

    @Test
    void appliesConfiguredCapacityAndPaginationLimits() {
        CatalogUseCase restricted = new CatalogUseCase(port, Clock.fixed(now, ZoneOffset.UTC),
            new CatalogLimits(120, 200, 2, 25, 2, 12, 8));

        StepVerifier
            .create(restricted.create("admin", "request-01", "Concert", "Arena", now.plusSeconds(60), 26))
            .expectErrorSatisfies(error -> assertEquals("INVALID_EVENT", ((DomainFailure) error).code()))
            .verify();
        StepVerifier.create(restricted.list(1, "cursor"))
            .expectErrorSatisfies(error -> assertEquals("INVALID_PAGE_SIZE", ((DomainFailure) error).code()))
            .verify();
        verifyNoInteractions(port);

        EventPage page = new EventPage(List.of(), null);
        when(port.list(8, "cursor")).thenReturn(Mono.just(page));

        StepVerifier.create(restricted.listDefaultPage("cursor")).expectNext(page).verifyComplete();
        verify(port).list(8, "cursor");
    }

    @Test
    void createNormalizesAndPersistsAtomicProvisioningIntent() {
        when(port.create(any(), anyString(), anyString(), any()))
            .thenAnswer(call -> Mono.just(call.getArgument(0, Event.class)));

        StepVerifier
            .create(useCase.create("admin", "request-01", " Concert ", " Arena ", now.plusSeconds(60), 20))
            .assertNext(event -> {
                assertEquals("Concert", event.name());
                assertEquals("Arena", event.venue());
                assertEquals("PROVISIONING", event.status());
                assertEquals(20, event.capacity());
            }).verifyComplete();

        ArgumentCaptor<Envelope> message = ArgumentCaptor.forClass(Envelope.class);
        verify(port).create(any(), eq(Validation.hash("admin\nrequest-01")),
            eq(Validation.hash("Concert\nArena\n2026-11-16T00:01:00Z\n20")), message.capture());
        assertEquals("ProvisionInventory", message.getValue().type());
        assertEquals(20, message.getValue().payload().get("capacity"));
        assertEquals(now, message.getValue().occurredAt());
    }

    @Test
    void creationPreservesValidationOrderBeforeReadingTheClockOrWriting() {
        Clock unusedClock = mock(Clock.class);
        CatalogUseCase validating = new CatalogUseCase(port, unusedClock,
            new CatalogLimits(120, 200, 1, 100_000, 1, 100, 50));

        StepVerifier.create(validating.create("admin", "bad", " ", " ", null, 0))
            .expectErrorSatisfies(error -> assertEquals("INVALID_INPUT", ((DomainFailure) error).code()))
            .verify();
        StepVerifier.create(validating.create("admin", "bad", "Concert", " ", null, 0))
            .expectErrorSatisfies(error -> assertEquals("INVALID_INPUT", ((DomainFailure) error).code()))
            .verify();
        StepVerifier.create(validating.create("admin", "bad", "Concert", "Arena", null, 0))
            .expectErrorSatisfies(
                error -> assertEquals("INVALID_IDEMPOTENCY_KEY", ((DomainFailure) error).code()))
            .verify();
        StepVerifier.create(validating.create("admin", "request-01", "Concert", "Arena", null, 0))
            .expectErrorSatisfies(error -> assertEquals("INVALID_EVENT", ((DomainFailure) error).code()))
            .verify();

        verifyNoInteractions(port, unusedClock);
    }

    @Test
    void creationDefersValidationAndPreservesBothClockReadings() {
        Clock changingClock = mock(Clock.class);
        when(changingClock.instant()).thenReturn(now, now.plusSeconds(1));
        CatalogUseCase deferredUseCase = new CatalogUseCase(port, changingClock,
            new CatalogLimits(120, 200, 1, 100_000, 1, 100, 50));
        when(port.create(any(), anyString(), anyString(), any()))
            .thenAnswer(call -> Mono.just(call.getArgument(0, Event.class)));

        Mono<Event> pending = deferredUseCase.create("admin", "request-01", "Concert", "Arena",
            now.plusSeconds(60), 20);
        verifyNoInteractions(port, changingClock);

        StepVerifier.create(pending).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Envelope> message = ArgumentCaptor.forClass(Envelope.class);
        verify(port).create(any(), anyString(), anyString(), message.capture());
        verify(changingClock, times(2)).instant();
        assertEquals(now.plusSeconds(1), message.getValue().occurredAt());
    }

    @Test
    void invalidEventNeverWrites() {
        for (Instant start : Arrays.asList(null, now, now.minusSeconds(1))) {
            StepVerifier.create(useCase.create("admin", "request-01", "Concert", "Arena", start, 1))
                .expectError(DomainFailure.class).verify();
        }

        for (int capacity : new int[] { 0, 100001 }) {
            StepVerifier
                .create(
                    useCase.create("admin", "request-01", "Concert", "Arena", now.plusSeconds(1), capacity))
                .expectError(DomainFailure.class).verify();
        }

        verifyNoInteractions(port);
    }

    @Test
    void acceptsInclusiveCapacityAndPaginationBounds() {
        when(port.create(any(), anyString(), anyString(), any()))
            .thenAnswer(call -> Mono.just(call.getArgument(0, Event.class)));

        for (int capacity : new int[] { 1, 100000 }) {
            StepVerifier
                .create(
                    useCase.create("admin", "request-01", "Concert", "Arena", now.plusSeconds(1), capacity))
                .assertNext(event -> assertEquals(capacity, event.capacity())).verifyComplete();
        }

        for (int limit : new int[] { 1, 100 }) {
            EventPage page = new EventPage(List.of(), null);
            when(port.list(limit, null)).thenReturn(Mono.just(page));
            StepVerifier.create(useCase.list(limit, null)).expectNext(page).verifyComplete();
            verify(port).list(limit, null);
        }
    }

    @Test
    void findAndPageRespectDomainFailures() {
        when(port.find("missing")).thenReturn(Mono.empty());
        StepVerifier.create(useCase.find("missing"))
            .expectErrorSatisfies(error -> assertEquals("EVENT_NOT_FOUND", ((DomainFailure) error).code()))
            .verify();

        for (int limit : new int[] { 0, 101 }) {
            StepVerifier.create(useCase.list(limit, null)).expectError(DomainFailure.class).verify();
        }

        EventPage page = new EventPage(List.of(), "next");
        when(port.list(10, "cursor")).thenReturn(Mono.just(page));
        StepVerifier.create(useCase.list(10, "cursor")).expectNext(page).verifyComplete();
    }

    @Test
    void receiveOnlySupportedInventoryReadyMessages() {
        Envelope ready = Envelope.create("InventoryReady", "event", Map.of("eventId", "event"), now);
        when(port.activate("event", ready.messageId())).thenReturn(Mono.empty());
        StepVerifier.create(useCase.receive(ready)).verifyComplete();
        verify(port).activate("event", ready.messageId());

        for (Envelope invalid : List.of(
            new Envelope("m", "InventoryReady", 2, "e", "e", "e", now, Map.of("eventId", "e")),
            Envelope.create("Other", "e", Map.of("eventId", "e"), now),
            Envelope.create("InventoryReady", "e", Map.of("eventId", 1), now))) {
            StepVerifier.create(useCase.receive(invalid)).expectError(DomainFailure.class).verify();
        }
    }
}
