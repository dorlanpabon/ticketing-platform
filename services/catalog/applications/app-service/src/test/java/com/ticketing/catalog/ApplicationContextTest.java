package com.ticketing.catalog;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketing.catalog.dynamodb.EventRepository;
import com.ticketing.catalog.sqs.listener.helper.SqsEventListener;
import com.ticketing.catalog.sqs.sender.OutboxRelay;
import com.ticketing.catalog.usecase.CatalogUseCase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = { "WORKERS_ENABLED=false",
    "CATALOG_EVENTS_QUEUE_URL=http://localhost:4566/catalog",
    "INVENTORY_COMMANDS_QUEUE_URL=http://localhost:4566/inventory", "SQS_ENDPOINT=http://localhost:4566",
    "spring.profiles.active=local", "AUTH_MODE=local", "DYNAMODB_ENDPOINT=http://localhost:8000" })
class ApplicationContextTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void scaffoldAdaptersAndUseCasesWireWithRealEnhancedSchema() {
        assertNotNull(context.getBean(EventRepository.class));
        assertNotNull(context.getBean(CatalogUseCase.class));
        assertTrue(context.getBeansOfType(SqsEventListener.class).isEmpty());
        assertTrue(context.getBeansOfType(OutboxRelay.class).isEmpty());
    }
}
