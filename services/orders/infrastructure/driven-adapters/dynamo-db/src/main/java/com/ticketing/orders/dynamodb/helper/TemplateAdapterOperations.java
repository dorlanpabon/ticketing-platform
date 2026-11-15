package com.ticketing.orders.dynamodb.helper;

import org.reactivecommons.utils.ObjectMapper;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PagePublisher;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.function.Function;

public abstract class TemplateAdapterOperations<E, K, V> {
    private final Class<V> dataClass;
    private final Function<V, E> toEntityFn;
    protected final ObjectMapper mapper;
    private final DynamoDbAsyncTable<V> table;
    private final DynamoDbAsyncIndex<V> tableByIndex;

    @SuppressWarnings("unchecked")
    protected TemplateAdapterOperations(DynamoDbEnhancedAsyncClient dynamoDbEnhancedAsyncClient,
                                        ObjectMapper mapper,
                                        Function<V, E> toEntityFn,
                                        String tableName,
                                        String... index) {
        this.toEntityFn = toEntityFn;
        this.mapper = mapper;
        ParameterizedType genericSuperclass = (ParameterizedType) this.getClass().getGenericSuperclass();
        this.dataClass = (Class<V>) genericSuperclass.getActualTypeArguments()[2];
        table = dynamoDbEnhancedAsyncClient.table(tableName, TableSchema.fromBean(dataClass));
        tableByIndex = index.length > 0 ? table.index(index[0]) : null;
    }

    protected TemplateAdapterOperations(DynamoDbEnhancedAsyncClient client, ObjectMapper mapper,
                                        Function<V,E> toEntityFn, Class<V> dataClass, String tableName, String... index) {
        this.toEntityFn = toEntityFn;
        this.mapper = mapper;
        this.dataClass = dataClass;
        table = client.table(tableName, TableSchema.fromBean(dataClass));
        tableByIndex = index.length > 0 ? table.index(index[0]) : null;
    }

    protected Mono<java.util.Map<String,AttributeValue>> getDocument(
            software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient client, String tableName,
            java.util.Map<String,AttributeValue> key) {
        return Mono.fromFuture(() -> client.getItem(software.amazon.awssdk.services.dynamodb.model.GetItemRequest.builder()
                .tableName(tableName).key(key).consistentRead(true).build()))
                .filter(response -> response.hasItem() && !response.item().isEmpty())
                .map(software.amazon.awssdk.services.dynamodb.model.GetItemResponse::item);
    }

    protected Mono<Void> saveTransaction(software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient client,
            java.util.List<software.amazon.awssdk.services.dynamodb.model.TransactWriteItem> writes) {
        return Mono.defer(() -> {
            String token=java.util.UUID.randomUUID().toString();
            return Mono.fromFuture(() -> client.transactWriteItems(software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest.builder()
                    .clientRequestToken(token).transactItems(writes).build()))
                    .retryWhen(reactor.util.retry.Retry.backoff(2,java.time.Duration.ofMillis(25)).maxBackoff(java.time.Duration.ofMillis(150))
                            .jitter(0.5).filter(TemplateAdapterOperations::transientFailure)
                            .onRetryExhaustedThrow((spec,signal) -> signal.failure())).then();
        });
    }

    protected static boolean transientFailure(Throwable error) {
        if(error instanceof software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException canceled)
            return canceled.cancellationReasons().stream().anyMatch(reason -> java.util.Set.of("TransactionConflict","ProvisionedThroughputExceeded","ThrottlingError").contains(reason.code()));
        return error instanceof software.amazon.awssdk.services.dynamodb.model.DynamoDbException service
                && (service.isThrottlingException() || service.statusCode()>=500);
    }

    public Mono<E> save(E model) {
        return Mono.fromFuture(() -> table.putItem(toEntity(model))).thenReturn(model);
    }

    public Mono<E> getById(K id) {
        return Mono.fromFuture(() -> table.getItem(Key.builder()
                        .partitionValue(AttributeValue.builder().s((String) id).build())
                        .build()))
                .map(this::toModel);
    }

    public Mono<E> delete(E model) {
        return Mono.fromFuture(() -> table.deleteItem(toEntity(model))).map(this::toModel);
    }

    public Mono<List<E>> query(QueryEnhancedRequest queryExpression) {
        PagePublisher<V> pagePublisher = table.query(queryExpression);
        return listOfModel(pagePublisher);
    }

    public Mono<List<E>> queryByIndex(QueryEnhancedRequest queryExpression, String... index) {
        DynamoDbAsyncIndex<V> queryIndex = index.length > 0 ? table.index(index[0]) : tableByIndex;

        SdkPublisher<Page<V>> pagePublisher = queryIndex.query(queryExpression);
        return listOfModel(pagePublisher);
    }

    /**
     * @return Mono<List < E>>
     * @implNote Bancolombia does not suggest the Scan function for DynamoDB tables due to the low performance resulting
     * from the design of the database engine (Key value). Optimize the query using Query, GetItem or BatchGetItem
     * functions, and if necessary, consider the Single-Table Design pattern for DynamoDB.
     * @deprecated
     */
    @Deprecated(forRemoval = true)
    public Mono<List<E>> scan() {
        return Mono.error(new UnsupportedOperationException("Use a bounded indexed query; table scans are disabled"));
    }

    private Mono<List<E>> listOfModel(PagePublisher<V> pagePublisher) {
        return Mono.from(pagePublisher).map(page -> page.items().stream().map(this::toModel).toList());
    }

    private Mono<List<E>> listOfModel(SdkPublisher<Page<V>> pagePublisher) {
        return Mono.from(pagePublisher).map(page -> page.items().stream().map(this::toModel).toList());
    }

    protected V toEntity(E model) {
        return mapper.map(model, dataClass);
    }

    protected E toModel(V data) {
        return data != null ? toEntityFn.apply(data) : null;
    }
}
