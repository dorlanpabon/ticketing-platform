package com.ticketing.inventory.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.ticketing.inventory.model.InventoryExecutionPolicy;
import com.ticketing.inventory.model.InventoryStore;
import com.ticketing.inventory.model.MessagePublisher;
import com.ticketing.inventory.model.OutboxStore;
import com.ticketing.inventory.usecase.InventoryUseCase;
import com.ticketing.inventory.usecase.OutboxDispatchPolicy;
import com.ticketing.inventory.usecase.OutboxUseCase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Duration;

class UseCasesConfigTest {

    @Test
    void bindsExecutionAndDispatchSettingsAndWiresBothUseCases() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("inventory.execution.transition-retries=1",
                "inventory.execution.processing-concurrency=3", "inventory.outbox.publish-concurrency=4",
                "inventory.outbox.dispatch-timeout=7s").applyTo(context);
            context.register(UseCasesConfig.class);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(InventoryStore.class, () -> mock(InventoryStore.class));
            context.registerBean(OutboxStore.class, () -> mock(OutboxStore.class));
            context.registerBean(MessagePublisher.class, () -> mock(MessagePublisher.class));
            context.refresh();

            var properties = context.getBean(UseCaseProperties.class);
            assertEquals(new InventoryExecutionPolicy(1, 3), properties.execution());
            assertEquals(new OutboxDispatchPolicy(4, Duration.ofSeconds(7)), properties.outbox());
            assertNotNull(context.getBean(InventoryUseCase.class));
            assertNotNull(context.getBean(OutboxUseCase.class));
        }
    }
}
