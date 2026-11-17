package com.ticketing.inventory.dynamodb.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.net.URI;
import java.time.Duration;

@Configuration
public class PersistenceConfig {

    @Bean
    public DynamoDbAsyncClient amazonDynamoDB(@Value("${aws.dynamodb.endpoint:}") String endpoint,
        @Value("${aws.region}") String region, MetricPublisher publisher) {
        var builder = DynamoDbAsyncClient.builder()
            .credentialsProvider(DefaultCredentialsProvider.builder().build()).region(Region.of(region))
            .overrideConfiguration(o -> o.addMetricPublisher(publisher).apiCallTimeout(Duration.ofSeconds(5))
                .apiCallAttemptTimeout(Duration.ofSeconds(2)));
        if (!endpoint.isBlank())
            builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedAsyncClient getDynamoDbEnhancedAsyncClient(DynamoDbAsyncClient client) {
        return DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build();
    }
}
