package com.ticketing.inventory.usecase;

import static com.ticketing.inventory.usecase.OutboxConstants.INVALID_DISPATCH_TIMEOUT;
import static com.ticketing.inventory.usecase.OutboxConstants.INVALID_PUBLISH_CONCURRENCY;
import static com.ticketing.inventory.usecase.OutboxConstants.MINIMUM_PUBLISH_CONCURRENCY;

import java.time.Duration;

public record OutboxDispatchPolicy(int publishConcurrency, Duration dispatchTimeout) {
    public OutboxDispatchPolicy {
        if (publishConcurrency < MINIMUM_PUBLISH_CONCURRENCY) {
            throw new IllegalArgumentException(INVALID_PUBLISH_CONCURRENCY);
        }
        if (dispatchTimeout == null || !dispatchTimeout.isPositive()) {
            throw new IllegalArgumentException(INVALID_DISPATCH_TIMEOUT);
        }
    }
}
