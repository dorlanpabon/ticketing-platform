package com.ticketing.catalog.dynamodb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.reactivecommons.utils.ObjectMapper;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

import java.time.Clock;

class TableEntitySchemaTest {
    @Test
    void adapterCanInitializeTheOutboxIndexWithoutNetworkAccess() {
        var client = mock(DynamoDbAsyncClient.class);
        var schema = TableSchema.fromBean(TableEntity.class);
        assertEquals("gsi1pk", schema.tableMetadata().indexPartitionKey("gsi1"));
        assertEquals("gsi1sk", schema.tableMetadata().indexSortKey("gsi1").orElseThrow());
        assertDoesNotThrow(() -> DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build()
            .table("catalog", schema).index("gsi1"));
        assertDoesNotThrow(
            () -> new EventRepository(client, "catalog", Clock.systemUTC(), mock(ObjectMapper.class)));
    }

    @Test
    void secondaryAttributesRoundTripThroughTheEnhancedSchema() {
        var entity = new TableEntity("OUTBOX#123", "payload");
        entity.setIndexPartitionKey("OUTBOX#1");
        entity.setIndexSortKey("0000000000000100");
        var schema = TableSchema.fromBean(TableEntity.class);
        var restored = schema.mapToItem(schema.itemToMap(entity, true));
        assertEquals(entity.getIndexPartitionKey(), restored.getIndexPartitionKey());
        assertEquals(entity.getIndexSortKey(), restored.getIndexSortKey());
        assertEquals("META", restored.getSortKey());
    }
}
