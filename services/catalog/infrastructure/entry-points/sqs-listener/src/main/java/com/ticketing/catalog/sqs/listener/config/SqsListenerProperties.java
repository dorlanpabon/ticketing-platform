package com.ticketing.catalog.sqs.listener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entrypoint.sqs")
public record SqsListenerProperties(String region, String endpoint, String queueUrl, int waitTimeSeconds,
    int visibilityTimeoutSeconds, int maxNumberOfMessages, int numberOfThreads) {
}
