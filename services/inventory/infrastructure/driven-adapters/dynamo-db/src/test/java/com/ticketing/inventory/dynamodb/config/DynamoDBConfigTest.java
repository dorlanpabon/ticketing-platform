package com.ticketing.inventory.dynamodb.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.metrics.MetricPublisher;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamoDBConfigTest {
    @Test void supportsLocalEndpointAndDefaultAwsProviderWithoutEmbeddedCredentials() {
        var config = new DynamoDBConfig();
        var metrics = mock(MetricPublisher.class);
        try (var local = config.amazonDynamoDB("http://localhost:18000", "us-east-1", metrics);
             var aws = config.amazonDynamoDB("", "us-east-1", metrics)) {
            assertTrue(local.serviceClientConfiguration().endpointOverride().isPresent());
            assertTrue(aws.serviceClientConfiguration().endpointOverride().isEmpty());
            assertNotNull(config.getDynamoDbEnhancedAsyncClient(local));
        }
    }
}
