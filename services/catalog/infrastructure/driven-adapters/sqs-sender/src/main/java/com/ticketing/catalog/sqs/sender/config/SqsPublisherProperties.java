package com.ticketing.catalog.sqs.sender.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "adapter.sqs")
public record SqsPublisherProperties(String region, String queueUrl, String endpoint) {
}
