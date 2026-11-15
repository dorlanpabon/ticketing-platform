package com.ticketing.inventory.sqs.sender.config;

import com.ticketing.inventory.sqs.sender.SQSSender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

@Configuration
public class SQSSenderConfig {
    @Bean public SQSSender sqsSender(SqsAsyncClient client, SQSSenderProperties properties, java.time.Clock clock) {
        return new SQSSender(client, properties.orderEventsQueue(), properties.catalogEventsQueue(), properties.expirationsQueue(), clock);
    }
}
