package com.ticketing.catalog.sqs.sender.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.net.URI;

@Configuration
@ConditionalOnMissingBean(SqsAsyncClient.class)
public class SqsPublisherConfig {

    @Bean
    public SqsAsyncClient configSqs(SqsPublisherProperties properties, MetricPublisher publisher) {
        return SqsAsyncClient.builder().endpointOverride(resolveEndpoint(properties))
            .region(Region.of(properties.region()))
            .overrideConfiguration(o -> o.addMetricPublisher(publisher))
            .credentialsProvider(DefaultCredentialsProvider.builder().build()).build();
    }

    private URI resolveEndpoint(SqsPublisherProperties properties) {
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            return URI.create(properties.endpoint());
        }
        return null;
    }
}
