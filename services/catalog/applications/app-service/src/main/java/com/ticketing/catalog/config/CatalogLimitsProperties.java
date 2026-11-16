package com.ticketing.catalog.config;

import com.ticketing.catalog.usecase.CatalogLimits;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("catalog.limits")
record CatalogLimitsProperties(int maxNameLength, int maxVenueLength, int minCapacity, int maxCapacity,
    int minPageSize, int maxPageSize, int defaultPageSize) {

    CatalogLimits toDomain() {
        return new CatalogLimits(maxNameLength, maxVenueLength, minCapacity, maxCapacity, minPageSize,
            maxPageSize, defaultPageSize);
    }
}
