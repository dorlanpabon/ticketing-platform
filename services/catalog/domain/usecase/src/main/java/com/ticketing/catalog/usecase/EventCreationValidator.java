package com.ticketing.catalog.usecase;

import com.ticketing.catalog.model.CatalogErrorCodes;
import com.ticketing.catalog.model.DomainFailure;
import com.ticketing.catalog.model.Validation;

import java.time.Clock;
import java.time.Instant;

/**
 * Normalizes event details and checks them against the configured catalog limits.
 */
final class EventCreationValidator {
    private final Clock clock;
    private final CatalogLimits limits;

    EventCreationValidator(Clock clock, CatalogLimits limits) {
        this.clock = clock;
        this.limits = limits;
    }

    ValidatedEventDetails validate(String key, String name, String venue, Instant startsAt, int capacity) {
        String validName = Validation.text(name, limits.maxNameLength());
        String validVenue = Validation.text(venue, limits.maxVenueLength());
        Validation.key(key);

        if (startsAt == null || !startsAt.isAfter(clock.instant()) || capacity < limits.minCapacity()
            || capacity > limits.maxCapacity()) {
            throw new DomainFailure(400, CatalogErrorCodes.INVALID_EVENT);
        }

        return new ValidatedEventDetails(validName, validVenue, startsAt, capacity);
    }
}
