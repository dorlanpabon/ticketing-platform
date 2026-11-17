package com.ticketing.inventory.dynamodb.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.metrics.MetricPublisher;

class PersistenceConfigTest {
    @Test
    void supportsLocalEndpointAndDefaultAwsProviderWithoutEmbeddedCredentials() {
        var config = new PersistenceConfig();
        var metrics = mock(MetricPublisher.class);
        try (var local = config.amazonDynamoDB("http://localhost:18000", "us-east-1", metrics);
            var aws = config.amazonDynamoDB("", "us-east-1", metrics)) {
            assertTrue(local.serviceClientConfiguration().endpointOverride().isPresent());
            assertTrue(aws.serviceClientConfiguration().endpointOverride().isEmpty());
            assertNotNull(config.getDynamoDbEnhancedAsyncClient(local));
        }
    }
}
