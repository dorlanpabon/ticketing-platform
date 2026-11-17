package com.ticketing.inventory.sqs.sender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inventory")
public record SqsPublisherProperties(String orderEventsQueue, String catalogEventsQueue,
    String expirationsQueue) {
}
