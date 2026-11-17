package com.ticketing.catalog.usecase;

import static com.ticketing.catalog.usecase.CatalogUseCaseConstants.CAPACITY_KEY;
import static com.ticketing.catalog.usecase.CatalogUseCaseConstants.EVENT_ID_KEY;
import static com.ticketing.catalog.usecase.CatalogUseCaseConstants.FINGERPRINT_SEPARATOR;
import static com.ticketing.catalog.usecase.CatalogUseCaseConstants.INVENTORY_READY_EVENT;
import static com.ticketing.catalog.usecase.CatalogUseCaseConstants.PROVISION_INVENTORY_COMMAND;

import com.ticketing.catalog.model.CatalogErrorCodes;
import com.ticketing.catalog.model.DomainFailure;
import com.ticketing.catalog.model.Envelope;
import com.ticketing.catalog.model.Event;
import com.ticketing.catalog.model.EventPage;
import com.ticketing.catalog.model.Validation;
import com.ticketing.catalog.model.gateways.CatalogPort;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Creates events and coordinates inventory provisioning before activation.
 */
public final class CatalogUseCase {
    private final CatalogPort port;
    private final Clock clock;
    private final CatalogLimits limits;
    private final EventCreationValidator creationValidator;

    public CatalogUseCase(CatalogPort port, Clock clock, CatalogLimits limits) {
        this.port = port;
        this.clock = clock;
        this.limits = limits;
        this.creationValidator = new EventCreationValidator(clock, limits);
    }

    public Mono<Event> create(String owner, String key, String name, String venue, Instant startsAt,
        int capacity) {
        return Mono.defer(() -> {
            ValidatedEventDetails details = creationValidator.validate(key, name, venue, startsAt, capacity);
            Event event = new Event(UUID.randomUUID().toString(), details.name(), details.venue(),
                details.startsAt(), details.capacity(), Event.PROVISIONING);
            String fingerprint = Validation.hash(details.name() + FINGERPRINT_SEPARATOR + details.venue()
                + FINGERPRINT_SEPARATOR + details.startsAt() + FINGERPRINT_SEPARATOR + details.capacity());
            Envelope message = Envelope.create(PROVISION_INVENTORY_COMMAND, event.eventId(),
                Map.of(EVENT_ID_KEY, event.eventId(), CAPACITY_KEY, details.capacity()), clock.instant());

            return port.create(event, Validation.hash(owner + FINGERPRINT_SEPARATOR + key), fingerprint,
                message);
        });
    }

    public Mono<Event> find(String id) {
        return port.find(id)
            .switchIfEmpty(Mono.error(new DomainFailure(404, CatalogErrorCodes.EVENT_NOT_FOUND)));
    }

    public Mono<EventPage> listDefaultPage(String cursor) {
        return list(limits.defaultPageSize(), cursor);
    }

    public Mono<EventPage> list(int limit, String cursor) {
        if (limit < limits.minPageSize() || limit > limits.maxPageSize()) {
            return Mono.error(new DomainFailure(400, CatalogErrorCodes.INVALID_PAGE_SIZE));
        }

        return port.list(limit, cursor);
    }

    public Mono<Void> receive(Envelope message) {
        if (message.schemaVersion() != Envelope.CURRENT_SCHEMA_VERSION
            || !message.type().equals(INVENTORY_READY_EVENT)) {
            return Mono.error(new DomainFailure(400, CatalogErrorCodes.UNSUPPORTED_MESSAGE));
        }

        Object id = message.payload().get(EVENT_ID_KEY);
        if (!(id instanceof String)) {
            return Mono.error(new DomainFailure(400, CatalogErrorCodes.INVALID_MESSAGE));
        }

        return port.activate((String) id, message.messageId());
    }
}
