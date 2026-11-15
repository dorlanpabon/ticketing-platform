package com.ticketing.catalog.dynamodb.helper;

import com.ticketing.catalog.dynamodb.ModelEntity;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

class TemplateAdapterOperationsTest {
    private static final class TestAdapter extends TemplateAdapterOperations<ModelEntity, String, ModelEntity> {
        TestAdapter(DynamoDbEnhancedAsyncClient client, ObjectMapper mapper) {
            super(client, mapper, Function.identity(), "table_name");
        }
    }

    @Mock
    private DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private DynamoDbAsyncTable<ModelEntity> customerTable;

    private ModelEntity modelEntity;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(dynamoDbEnhancedAsyncClient.table("table_name", TableSchema.fromBean(ModelEntity.class)))
                .thenReturn(customerTable);

        modelEntity = new ModelEntity();
        modelEntity.setId("id");
        modelEntity.setAtr1("atr1");
    }

    @Test
    void modelEntityPropertiesMustNotBeNull() {
        ModelEntity modelEntityUnderTest = new ModelEntity("id", "atr1");

        assertNotNull(modelEntityUnderTest.getId());
        assertNotNull(modelEntityUnderTest.getAtr1());
    }

    @Test
    void testSave() {
        when(customerTable.putItem(modelEntity)).thenReturn(CompletableFuture.runAsync(()->{}));
        when(mapper.map(modelEntity, ModelEntity.class)).thenReturn(modelEntity);

        TestAdapter dynamoDBTemplateAdapter =
                new TestAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.save(modelEntity))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void testGetById() {
        String id = "id";

        when(customerTable.getItem(
                Key.builder().partitionValue(AttributeValue.builder().s(id).build()).build()))
                .thenReturn(CompletableFuture.completedFuture(modelEntity));

        TestAdapter dynamoDBTemplateAdapter =
                new TestAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.getById("id"))
                .expectNext(modelEntity)
                .verifyComplete();
    }

    @Test
    void testDelete() {
        when(mapper.map(modelEntity, ModelEntity.class)).thenReturn(modelEntity);

        when(customerTable.deleteItem(modelEntity))
                .thenReturn(CompletableFuture.completedFuture(modelEntity));

        TestAdapter dynamoDBTemplateAdapter =
                new TestAdapter(dynamoDbEnhancedAsyncClient, mapper);

        StepVerifier.create(dynamoDBTemplateAdapter.delete(modelEntity))
                .expectNext(modelEntity)
                .verifyComplete();
    }
}
