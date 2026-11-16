package com.ticketing.inventory.config;

import com.ticketing.inventory.model.InventoryStore;
import com.ticketing.inventory.model.MessagePublisher;
import com.ticketing.inventory.model.OutboxStore;
import com.ticketing.inventory.usecase.InventoryUseCase;
import com.ticketing.inventory.usecase.OutboxUseCase;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UseCaseProperties.class)
public class UseCasesConfig {
    @Bean
    InventoryUseCase inventoryUseCase(InventoryStore store, Clock clock, UseCaseProperties properties) {
        return new InventoryUseCase(store, clock, properties.execution());
    }

    @Bean
    OutboxUseCase outboxUseCase(OutboxStore store, MessagePublisher publisher, Clock clock,
        UseCaseProperties properties) {
        return new OutboxUseCase(store, publisher, clock, properties.outbox());
    }
}
