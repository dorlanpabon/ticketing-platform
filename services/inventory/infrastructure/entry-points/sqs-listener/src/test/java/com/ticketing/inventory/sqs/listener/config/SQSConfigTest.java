package com.ticketing.inventory.sqs.listener.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.metrics.MetricPublisher;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SQSConfigTest {
    @Test void localEndpointOverrideIsOptional() {
        var config = new SQSConfig();
        var metrics = mock(MetricPublisher.class);
        try (var local = config.sqsAsyncClient("us-east-1", new SQSProperties("http://localhost:19324"), metrics);
             var aws = config.sqsAsyncClient("us-east-1", new SQSProperties(""), metrics);
             var absent = config.sqsAsyncClient("us-east-1", new SQSProperties(null), metrics)) {
            assertTrue(local.serviceClientConfiguration().endpointOverride().isPresent());
            assertTrue(aws.serviceClientConfiguration().endpointOverride().isEmpty());
            assertTrue(absent.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }
}
