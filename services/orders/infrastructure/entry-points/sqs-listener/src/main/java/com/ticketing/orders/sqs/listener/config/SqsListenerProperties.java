package com.ticketing.orders.sqs.listener.config;

import com.ticketing.orders.sqs.listener.utils.SqsConstants;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = SqsConstants.PROPERTIES_PREFIX)
public record SqsListenerProperties(String region, String endpoint, String queueUrl, int waitTimeSeconds,
    int visibilityTimeoutSeconds, int maxNumberOfMessages, int numberOfThreads) {
}
