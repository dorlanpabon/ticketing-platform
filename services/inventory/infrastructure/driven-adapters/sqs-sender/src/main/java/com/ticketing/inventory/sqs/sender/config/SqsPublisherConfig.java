package com.ticketing.inventory.sqs.sender.config;

import com.ticketing.inventory.sqs.sender.SqsMessagePublisher;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Clock;

@Configuration
public class SqsPublisherConfig {
    @Bean
    public SqsMessagePublisher sqsSender(SqsAsyncClient client, SqsPublisherProperties properties,
        Clock clock) {
        return new SqsMessagePublisher(client, properties.orderEventsQueue(), properties.catalogEventsQueue(),
            properties.expirationsQueue(), clock);
    }
}
