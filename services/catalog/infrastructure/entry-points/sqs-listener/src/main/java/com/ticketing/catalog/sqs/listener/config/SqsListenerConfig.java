package com.ticketing.catalog.sqs.listener.config;

import com.ticketing.catalog.sqs.listener.helper.SqsEventListener;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.net.URI;
import java.util.function.Function;

@Configuration
public class SqsListenerConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "WORKERS_ENABLED", havingValue = "true")
    public SqsEventListener sqsListener(SqsAsyncClient client, SqsListenerProperties properties,
        Function<Message, Mono<Void>> fn) {
        return SqsEventListener.builder().client(client).properties(properties).processor(fn).build().start();
    }

    @Bean
    public SqsAsyncClient configSqs(SqsListenerProperties properties, MetricPublisher publisher) {
        return SqsAsyncClient.builder().endpointOverride(resolveEndpoint(properties))
            .region(Region.of(properties.region()))
            .overrideConfiguration(o -> o.addMetricPublisher(publisher))
            .credentialsProvider(DefaultCredentialsProvider.builder().build()).build();
    }

    protected URI resolveEndpoint(SqsListenerProperties properties) {
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            return URI.create(properties.endpoint());
        }
        return null;
    }
}
