package com.ticketing.inventory.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InventoryExecutionPolicyTest {
    @Test
    void allowsDisabledRetriesAndSingleProcessingSlotButRejectsInvalidBudgets() {
        assertDoesNotThrow(() -> new InventoryExecutionPolicy(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new InventoryExecutionPolicy(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new InventoryExecutionPolicy(0, 0));
    }
}
