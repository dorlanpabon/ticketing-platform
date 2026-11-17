package com.ticketing.inventory.sqs.listener.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.metrics.MetricPublisher;

class SqsClientConfigTest {
    @Test
    void localEndpointOverrideIsOptional() {
        var config = new SqsClientConfig();
        var metrics = mock(MetricPublisher.class);
        try (
            var local = config.sqsAsyncClient("us-east-1", new SqsClientProperties("http://localhost:4566"),
                metrics);
            var aws = config.sqsAsyncClient("us-east-1", new SqsClientProperties(""), metrics);
            var absent = config.sqsAsyncClient("us-east-1", new SqsClientProperties(null), metrics)) {
            assertTrue(local.serviceClientConfiguration().endpointOverride().isPresent());
            assertTrue(aws.serviceClientConfiguration().endpointOverride().isEmpty());
            assertTrue(absent.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }
}
