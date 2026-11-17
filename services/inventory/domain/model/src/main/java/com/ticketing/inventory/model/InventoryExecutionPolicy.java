package com.ticketing.inventory.model;

public record InventoryExecutionPolicy(long transitionRetries, int processingConcurrency) {
    public InventoryExecutionPolicy {
        if (transitionRetries < 0 || processingConcurrency < 1) {
            throw new IllegalArgumentException(InventoryErrorCodes.INVALID_EXECUTION_POLICY);
        }
    }
}
