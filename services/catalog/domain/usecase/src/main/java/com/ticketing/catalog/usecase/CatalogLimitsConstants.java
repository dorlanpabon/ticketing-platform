package com.ticketing.catalog.usecase;

final class CatalogLimitsConstants {
    static final int CONTRACT_MAX_NAME_LENGTH = 120;
    static final int CONTRACT_MAX_VENUE_LENGTH = 200;
    static final int CONTRACT_MAX_CAPACITY = 100_000;
    static final int CONTRACT_MAX_PAGE_SIZE = 100;

    static final String MAX_NAME_LENGTH_PROPERTY = "max-name-length";
    static final String MAX_VENUE_LENGTH_PROPERTY = "max-venue-length";
    static final String MAX_CAPACITY_PROPERTY = "max-capacity";
    static final String MIN_CAPACITY_PROPERTY = "min-capacity";
    static final String MAX_PAGE_SIZE_PROPERTY = "max-page-size";
    static final String MIN_PAGE_SIZE_PROPERTY = "min-page-size";
    static final String DEFAULT_PAGE_SIZE_PROPERTY = "default-page-size";
    static final String RANGE_LOWER_BOUND_MESSAGE = " must be between ";
    static final String RANGE_UPPER_BOUND_MESSAGE = " and ";

    private CatalogLimitsConstants() {
    }
}
