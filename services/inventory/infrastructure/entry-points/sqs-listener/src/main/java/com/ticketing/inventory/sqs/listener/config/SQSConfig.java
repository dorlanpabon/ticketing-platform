package com.ticketing.inventory.sqs.listener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;
import java.time.Duration;

@Configuration
public class SQSConfig {

    @Bean
    public SqsAsyncClient sqsAsyncClient(@Value("${aws.region}") String region,
            SQSProperties properties, MetricPublisher publisher) {
        var builder = SqsAsyncClient.builder().region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .overrideConfiguration(config -> config.addMetricPublisher(publisher)
                        .apiCallTimeout(Duration.ofSeconds(35)).apiCallAttemptTimeout(Duration.ofSeconds(30)));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) builder.endpointOverride(URI.create(properties.endpoint()));
        return builder.build();
    }
}
