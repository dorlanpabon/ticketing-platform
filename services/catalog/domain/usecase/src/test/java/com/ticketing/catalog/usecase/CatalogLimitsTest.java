package com.ticketing.catalog.usecase;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CatalogLimitsTest {
    @Test
    void rejectsEventLimitsOutsideTheContract() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogLimits(0, 200, 2, 25, 2, 12, 8));
        assertThrows(IllegalArgumentException.class, () -> new CatalogLimits(120, 201, 2, 25, 2, 12, 8));
        assertThrows(IllegalArgumentException.class, () -> new CatalogLimits(120, 200, 2, 100_001, 2, 12, 8));
        assertThrows(IllegalArgumentException.class, () -> new CatalogLimits(120, 200, 26, 25, 2, 12, 8));
    }

    @Test
    void rejectsPaginationLimitsOutsideTheConfiguredRange() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogLimits(120, 200, 2, 25, 2, 101, 8));
        assertThrows(IllegalArgumentException.class, () -> new CatalogLimits(120, 200, 2, 25, 13, 12, 8));
        assertThrows(IllegalArgumentException.class, () -> new CatalogLimits(120, 200, 2, 25, 2, 12, 13));
    }
}
