package com.ticketing.inventory.config;

import com.ticketing.inventory.model.InventoryExecutionPolicy;
import com.ticketing.inventory.usecase.OutboxDispatchPolicy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("inventory")
record UseCaseProperties(InventoryExecutionPolicy execution, OutboxDispatchPolicy outbox) {
}
