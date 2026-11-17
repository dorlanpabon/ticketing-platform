package com.ticketing.orders.sqs.sender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "adapter.sqs.enqueue")
public record SqsEnqueueProperties(Duration timeout, Duration retryDelay) {
    public SqsEnqueueProperties {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Order enqueue timeout must be positive");
        }
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("Order enqueue retry delay must be positive");
        }
    }
}
