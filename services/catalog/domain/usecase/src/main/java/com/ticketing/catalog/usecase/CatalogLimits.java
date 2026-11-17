package com.ticketing.catalog.usecase;

import static com.ticketing.catalog.usecase.CatalogLimitsConstants.CONTRACT_MAX_CAPACITY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.CONTRACT_MAX_NAME_LENGTH;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.CONTRACT_MAX_PAGE_SIZE;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.CONTRACT_MAX_VENUE_LENGTH;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.DEFAULT_PAGE_SIZE_PROPERTY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.MAX_CAPACITY_PROPERTY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.MAX_NAME_LENGTH_PROPERTY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.MAX_PAGE_SIZE_PROPERTY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.MAX_VENUE_LENGTH_PROPERTY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.MIN_CAPACITY_PROPERTY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.MIN_PAGE_SIZE_PROPERTY;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.RANGE_LOWER_BOUND_MESSAGE;
import static com.ticketing.catalog.usecase.CatalogLimitsConstants.RANGE_UPPER_BOUND_MESSAGE;

public record CatalogLimits(int maxNameLength, int maxVenueLength, int minCapacity, int maxCapacity,
    int minPageSize, int maxPageSize, int defaultPageSize) {

    public CatalogLimits {
        requireBetween(maxNameLength, 1, CONTRACT_MAX_NAME_LENGTH, MAX_NAME_LENGTH_PROPERTY);
        requireBetween(maxVenueLength, 1, CONTRACT_MAX_VENUE_LENGTH, MAX_VENUE_LENGTH_PROPERTY);
        requireBetween(maxCapacity, 1, CONTRACT_MAX_CAPACITY, MAX_CAPACITY_PROPERTY);
        requireBetween(minCapacity, 1, maxCapacity, MIN_CAPACITY_PROPERTY);
        requireBetween(maxPageSize, 1, CONTRACT_MAX_PAGE_SIZE, MAX_PAGE_SIZE_PROPERTY);
        requireBetween(minPageSize, 1, maxPageSize, MIN_PAGE_SIZE_PROPERTY);
        requireBetween(defaultPageSize, minPageSize, maxPageSize, DEFAULT_PAGE_SIZE_PROPERTY);
    }

    private static void requireBetween(int value, int min, int max, String property) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                property + RANGE_LOWER_BOUND_MESSAGE + min + RANGE_UPPER_BOUND_MESSAGE + max);
        }
    }
}
