package com.ticketing.orders.dynamodb.helper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import com.ticketing.orders.dynamodb.TableEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.reactivecommons.utils.ObjectMapper;

import reactor.test.StepVerifier;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.concurrent.CompletableFuture;

class AbstractTableOperationsTest {

    @Mock
    private DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private DynamoDbAsyncTable<TableEntity> customerTable;

    private TableEntity document;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(dynamoDbEnhancedAsyncClient.table("table_name", TableSchema.fromBean(TableEntity.class)))
            .thenReturn(customerTable);

        document = new TableEntity();
        document.setPartitionKey("id");
        document.setPayloadJson("atr1");
    }

    @Test
    void modelEntityPropertiesMustNotBeNull() {
        TableEntity documentUnderTest = new TableEntity("id", "atr1");

        assertNotNull(documentUnderTest.getPartitionKey());
        assertNotNull(documentUnderTest.getPayloadJson());
    }

    @Test
    void testSave() {
        when(customerTable.putItem(document)).thenReturn(CompletableFuture.runAsync(() -> {
        }));
        when(mapper.map(document, TableEntity.class)).thenReturn(document);

        ExampleAdapter dynamoDBTemplateAdapter = new ExampleAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.save(document)).expectNextCount(1).verifyComplete();
    }

    @Test
    void testGetById() {
        String id = "id";

        when(customerTable
            .getItem(Key.builder().partitionValue(AttributeValue.builder().s(id).build()).build()))
            .thenReturn(CompletableFuture.completedFuture(document));
        when(mapper.map(document, Object.class)).thenReturn("value");

        ExampleAdapter dynamoDBTemplateAdapter = new ExampleAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.getById("id")).expectNext("value").verifyComplete();
    }

    @Test
    void testDelete() {
        when(mapper.map(document, TableEntity.class)).thenReturn(document);
        when(mapper.map(document, Object.class)).thenReturn("value");

        when(customerTable.deleteItem(document)).thenReturn(CompletableFuture.completedFuture(document));

        ExampleAdapter dynamoDBTemplateAdapter = new ExampleAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.delete(document)).expectNext("value").verifyComplete();
    }

    static final class ExampleAdapter extends AbstractTableOperations<Object, String, TableEntity> {
        ExampleAdapter(DynamoDbEnhancedAsyncClient client, ObjectMapper mapper) {
            super(client, mapper, item -> mapper.map(item, Object.class), "table_name");
        }
    }
}
