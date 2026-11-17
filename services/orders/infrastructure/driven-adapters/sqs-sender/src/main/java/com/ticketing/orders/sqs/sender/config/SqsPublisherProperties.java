package com.ticketing.orders.sqs.sender.config;

import com.ticketing.orders.sqs.sender.utils.SqsConstants;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = SqsConstants.PROPERTIES_PREFIX)
public record SqsPublisherProperties(String region, String queueUrl, String endpoint) {
}
