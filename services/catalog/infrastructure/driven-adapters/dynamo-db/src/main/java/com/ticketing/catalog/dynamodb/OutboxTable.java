package com.ticketing.catalog.dynamodb;

import com.ticketing.catalog.dynamodb.utils.PersistenceConstants;

import com.ticketing.catalog.dynamodb.helper.AbstractTableOperations;
import com.ticketing.catalog.model.CatalogErrorCodes;
import com.ticketing.catalog.model.DomainFailure;
import com.ticketing.catalog.model.Envelope;
import com.ticketing.catalog.model.OutboxEntry;
import com.ticketing.catalog.model.gateways.OutboxPort;

import org.reactivecommons.utils.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public abstract class OutboxTable extends AbstractTableOperations<TableEntity, String, TableEntity>
    implements OutboxPort {
    protected final DynamoDbAsyncClient client;
    protected final String table;
    protected final Clock clock;
    private final PollingCoordinator outboxPolling;
    protected final JsonMapper jsonMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    protected OutboxTable(DynamoDbAsyncClient client, String table, Clock clock, ObjectMapper objectMapper) {
        super(DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build(), objectMapper,
            Function.identity(), TableEntity.class, table, PersistenceConstants.OUTBOX_INDEX_NAME);
        this.client = client;
        this.table = table;
        this.clock = clock;
        this.outboxPolling = new PollingCoordinator(client, table, clock,
            PersistenceConstants.OUTBOX_COORDINATOR, 10000);
    }

    protected static AttributeValue text(String value) {
        return AttributeValue.fromS(value);
    }

    protected static AttributeValue number(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    protected static Map<String, AttributeValue> key(String key) {
        return Map.of(PersistenceConstants.PARTITION_KEY, text(key), PersistenceConstants.SORT_KEY,
            text(PersistenceConstants.METADATA_SORT_KEY));
    }

    protected Mono<Map<String, AttributeValue>> get(String id) {
        return getDocument(client, table, key(id));
    }

    protected <T> T decode(Map<String, AttributeValue> item, Class<T> type) {
        return jsonMapper.readValue(item.get(PersistenceConstants.DATA_ATTRIBUTE).s(), type);
    }

    protected Map<String, AttributeValue> item(String id, Object data) {
        return new TableEntity(id, jsonMapper.writeValueAsString(data)).attributes();
    }

    protected TransactWriteItem put(Map<String, AttributeValue> item, String condition) {
        return TransactWriteItem.builder()
            .put(Put.builder().tableName(table).item(item).conditionExpression(condition).build()).build();
    }

    protected Mono<Void> transact(List<TransactWriteItem> writes) {
        return saveTransaction(client, writes);
    }

    protected TransactWriteItem outbox(Envelope message) {
        Map<String, AttributeValue> row = item(PersistenceConstants.OUTBOX_PREFIX + message.messageId(),
            message);
        row.put(PersistenceConstants.OUTBOX_PARTITION_KEY, text(PersistenceConstants.OUTBOX_PREFIX
            + Math.floorMod(message.messageId().hashCode(), PersistenceConstants.SHARD_COUNT)));
        row.put(PersistenceConstants.OUTBOX_SORT_KEY, text(time(clock.millis())));
        row.put(PersistenceConstants.LEASE_UNTIL, number(0));
        return put(row, PersistenceConstants.ATTRIBUTE_NOT_EXISTS);
    }

    protected TransactWriteItem idempotency(String key, String fingerprint, String id) {
        Map<String, AttributeValue> row = new HashMap<>(key(PersistenceConstants.IDEMPOTENCY_PREFIX + key));
        row.put(PersistenceConstants.FINGERPRINT, text(fingerprint));
        row.put(PersistenceConstants.ENTITY_ID, text(id));
        row.put(PersistenceConstants.TTL, number(clock.instant().plusSeconds(2592000).getEpochSecond()));
        return put(row, PersistenceConstants.ATTRIBUTE_NOT_EXISTS);
    }

    protected Mono<String> previous(String key, String fingerprint) {
        return get(PersistenceConstants.IDEMPOTENCY_PREFIX + key)
            .flatMap(row -> row.get(PersistenceConstants.FINGERPRINT).s().equals(fingerprint)
                ? Mono.just(row.get(PersistenceConstants.ENTITY_ID).s())
                : Mono.error(new DomainFailure(409, CatalogErrorCodes.IDEMPOTENCY_CONFLICT)));
    }

    protected TransactWriteItem inbox(String id) {
        Map<String, AttributeValue> row = new HashMap<>(key(PersistenceConstants.INBOX_PREFIX + id));
        row.put(PersistenceConstants.TTL, number(clock.instant().plusSeconds(2592000).getEpochSecond()));
        return put(row, PersistenceConstants.ATTRIBUTE_NOT_EXISTS);
    }

    protected static String time(long millis) {
        return String.format(Locale.ROOT, PersistenceConstants.TIME_SORT_FORMAT, millis);
    }

    protected Flux<Map<String, AttributeValue>> dueRows(String partition) {
        return Mono
            .fromFuture(() -> client.query(QueryRequest.builder().tableName(table)
                .indexName(PersistenceConstants.OUTBOX_INDEX_NAME)
                .keyConditionExpression(PersistenceConstants.DUE_OUTBOX_CONDITION)
                .expressionAttributeValues(Map.of(PersistenceConstants.PARTITION_PARAMETER, text(partition),
                    PersistenceConstants.NOW_PARAMETER, text(time(clock.millis()))))
                .limit(25).build()))
            .flatMapMany(result -> Flux.fromIterable(result.items()));
    }

    @Override
    public Flux<OutboxEntry> claim() {
        return outboxPolling.poll(this::claimPending);
    }

    private Flux<OutboxEntry> claimPending() {
        return Flux.range(0, PersistenceConstants.SHARD_COUNT)
            .flatMap(shard -> dueRows(PersistenceConstants.OUTBOX_PREFIX + shard), 4).flatMap(row -> {
                String owner = UUID.randomUUID().toString();
                long now = clock.millis();
                return Mono
                    .fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                        .key(key(row.get(PersistenceConstants.PARTITION_KEY).s()))
                        .conditionExpression(PersistenceConstants.AVAILABLE_OUTBOX_LEASE)
                        .updateExpression(PersistenceConstants.CLAIM_OUTBOX_LEASE)
                        .expressionAttributeValues(Map.of(PersistenceConstants.NOW_PARAMETER, number(now),
                            PersistenceConstants.UNTIL_PARAMETER, number(now + 30000),
                            PersistenceConstants.OWNER_PARAMETER, text(owner),
                            PersistenceConstants.DUE_PARAMETER, text(time(now + 30000))))
                        .returnValues(ReturnValue.ALL_NEW).build()))
                    .map(result -> new OutboxEntry(row.get(PersistenceConstants.PARTITION_KEY).s(), owner,
                        decode(result.attributes(), Envelope.class)))
                    .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
            }, 8);
    }

    @Override
    public Mono<Void> acknowledge(OutboxEntry entry) {
        return Mono
            .fromFuture(() -> client.deleteItem(DeleteItemRequest.builder().tableName(table)
                .key(key(entry.key())).conditionExpression(PersistenceConstants.OWNED_LEASE)
                .expressionAttributeValues(
                    Map.of(PersistenceConstants.OWNER_PARAMETER, text(entry.leaseOwner())))
                .build()))
            .then().onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }
}
