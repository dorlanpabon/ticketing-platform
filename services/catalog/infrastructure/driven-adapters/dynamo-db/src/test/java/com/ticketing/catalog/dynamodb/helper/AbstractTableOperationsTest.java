package com.ticketing.catalog.dynamodb.helper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import com.ticketing.catalog.dynamodb.TableEntity;

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
import java.util.function.Function;

class AbstractTableOperationsTest {
    private static final class TestAdapter extends AbstractTableOperations<TableEntity, String, TableEntity> {
        TestAdapter(DynamoDbEnhancedAsyncClient client, ObjectMapper mapper) {
            super(client, mapper, Function.identity(), "table_name");
        }
    }

    @Mock
    private DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private DynamoDbAsyncTable<TableEntity> customerTable;

    private TableEntity modelEntity;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(dynamoDbEnhancedAsyncClient.table("table_name", TableSchema.fromBean(TableEntity.class)))
            .thenReturn(customerTable);

        modelEntity = new TableEntity();
        modelEntity.setId("id");
        modelEntity.setPayload("atr1");
    }

    @Test
    void modelEntityPropertiesMustNotBeNull() {
        TableEntity modelEntityUnderTest = new TableEntity("id", "atr1");

        assertNotNull(modelEntityUnderTest.getId());
        assertNotNull(modelEntityUnderTest.getPayload());
    }

    @Test
    void testSave() {
        when(customerTable.putItem(modelEntity)).thenReturn(CompletableFuture.runAsync(() -> {
        }));
        when(mapper.map(modelEntity, TableEntity.class)).thenReturn(modelEntity);

        TestAdapter dynamoDBTemplateAdapter = new TestAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.save(modelEntity)).expectNextCount(1).verifyComplete();
    }

    @Test
    void testGetById() {
        String id = "id";

        when(customerTable
            .getItem(Key.builder().partitionValue(AttributeValue.builder().s(id).build()).build()))
            .thenReturn(CompletableFuture.completedFuture(modelEntity));

        TestAdapter dynamoDBTemplateAdapter = new TestAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.getById("id")).expectNext(modelEntity).verifyComplete();
    }

    @Test
    void testDelete() {
        when(mapper.map(modelEntity, TableEntity.class)).thenReturn(modelEntity);

        when(customerTable.deleteItem(modelEntity))
            .thenReturn(CompletableFuture.completedFuture(modelEntity));

        TestAdapter dynamoDBTemplateAdapter = new TestAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.delete(modelEntity)).expectNext(modelEntity)
            .verifyComplete();
    }
}
