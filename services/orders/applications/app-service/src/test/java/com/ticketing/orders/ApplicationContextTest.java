package com.ticketing.orders;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest(properties = { "spring.profiles.active=local", "AUTH_MODE=local", "workers.enabled=false",
    "DYNAMODB_ENDPOINT=http://localhost:18000", "SQS_ENDPOINT=http://localhost:19324",
    "ORDER_EVENTS_QUEUE_URL=http://localhost:19324/000000000000/order-events",
    "INVENTORY_COMMANDS_QUEUE_URL=http://localhost:19324/000000000000/inventory-commands",
    "INVENTORY_BASE_URL=http://localhost:18083" })
class ApplicationContextTest {
    @Autowired
    ApplicationContext context;

    @Test
    void assemblesGeneratedModulesIncludingHttpClientAndIndexedPersistence() {
        assertNotNull(context.getBean(WebClient.Builder.class));
        assertNotNull(context.getBean("dynamoDBTemplateAdapter"));
        assertNotNull(context.getBean("restConsumer"));
        assertNotNull(context.getBean("ordersUseCase"));
        assertFalse(context.containsBean("sqsListener"));
    }
}
