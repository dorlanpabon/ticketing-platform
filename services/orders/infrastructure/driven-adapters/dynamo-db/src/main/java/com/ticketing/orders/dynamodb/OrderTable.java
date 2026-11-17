package com.ticketing.orders.dynamodb;

import com.ticketing.orders.dynamodb.utils.PersistenceConstants;

import com.ticketing.orders.dynamodb.helper.AbstractTableOperations;
import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.OutboxEntry;
import com.ticketing.orders.model.gateways.OutboxPort;

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

public abstract class OrderTable extends AbstractTableOperations<TableEntity, String, TableEntity>
    implements OutboxPort {
    protected final DynamoDbAsyncClient client;
    protected final String table;
    protected final Clock clock;
    private final PollingCoordinator outboxPolling;
    protected final JsonMapper jsonMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    protected OrderTable(DynamoDbAsyncClient client, String table, Clock clock, ObjectMapper objectMapper) {
        super(DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build(), objectMapper,
            Function.identity(), TableEntity.class, table, PersistenceConstants.DUE_INDEX);
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
        return jsonMapper.readValue(item.get(PersistenceConstants.PAYLOAD).s(), type);
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
        row.put(PersistenceConstants.DUE_PARTITION_KEY,
            text(PersistenceConstants.OUTBOX_PREFIX + Math.floorMod(message.messageId().hashCode(), 16)));
        row.put(PersistenceConstants.DUE_SORT_KEY, text(time(clock.millis())));
        row.put(PersistenceConstants.LEASE_UNTIL, number(0));
        return put(row, PersistenceConstants.ROW_MUST_NOT_EXIST);
    }

    protected TransactWriteItem idempotency(String key, String fingerprint, String id) {
        return put(idempotencyRow(key, fingerprint, id), PersistenceConstants.ROW_MUST_NOT_EXIST);
    }

    protected TransactWriteItem submissionIdempotency(String key, String fingerprint, String id,
        String messageId) {
        var row = idempotencyRow(key, fingerprint, id);
        row.put(PersistenceConstants.PURCHASE_MESSAGE_ID, text(messageId));
        return put(row, PersistenceConstants.ROW_MUST_NOT_EXIST);
    }

    private Map<String, AttributeValue> idempotencyRow(String key, String fingerprint, String id) {
        Map<String, AttributeValue> row = new HashMap<>(key(PersistenceConstants.IDEMPOTENCY_PREFIX + key));
        row.put(PersistenceConstants.FINGERPRINT_ATTRIBUTE, text(fingerprint));
        row.put(PersistenceConstants.ENTITY_ID_ATTRIBUTE, text(id));
        row.put(PersistenceConstants.TTL, number(clock.instant().plusSeconds(2592000).getEpochSecond()));
        return row;
    }

    protected Mono<String> previous(String key, String fingerprint) {
        return idempotencyRecord(key, fingerprint)
            .map(row -> row.get(PersistenceConstants.ENTITY_ID_ATTRIBUTE).s());
    }

    protected Mono<Map<String, AttributeValue>> idempotencyRecord(String key, String fingerprint) {
        return get(PersistenceConstants.IDEMPOTENCY_PREFIX + key)
            .flatMap(row -> row.get(PersistenceConstants.FINGERPRINT_ATTRIBUTE).s().equals(fingerprint)
                ? Mono.just(row)
                : Mono.error(new DomainFailure(409, PersistenceConstants.IDEMPOTENCY_CONFLICT)));
    }

    protected TransactWriteItem inbox(String id) {
        Map<String, AttributeValue> row = new HashMap<>(key(PersistenceConstants.INBOX_PREFIX + id));
        row.put(PersistenceConstants.TTL, number(clock.instant().plusSeconds(2592000).getEpochSecond()));
        return put(row, PersistenceConstants.ROW_MUST_NOT_EXIST);
    }

    protected static String time(long millis) {
        return String.format(Locale.ROOT, PersistenceConstants.TIMESTAMP_FORMAT, millis);
    }

    protected Flux<Map<String, AttributeValue>> dueRows(String partition) {
        return Mono
            .fromFuture(() -> client
                .query(QueryRequest.builder().tableName(table).indexName(PersistenceConstants.DUE_INDEX)
                    .keyConditionExpression(PersistenceConstants.DUE_QUERY)
                    .expressionAttributeValues(Map.of(PersistenceConstants.PARTITION_VALUE, text(partition),
                        PersistenceConstants.NOW_VALUE, text(time(clock.millis()))))
                    .limit(25).build()))
            .flatMapMany(result -> Flux.fromIterable(result.items()));
    }

    @Override
    public Flux<OutboxEntry> claim() {
        return outboxPolling.poll(this::claimPending);
    }

    private Flux<OutboxEntry> claimPending() {
        return Flux.range(0, 16).flatMap(shard -> dueRows(PersistenceConstants.OUTBOX_PREFIX + shard), 4)
            .flatMap(row -> claimKey(row.get(PersistenceConstants.PARTITION_KEY).s()), 8);
    }

    @Override
    public Mono<OutboxEntry> claim(String messageId) {
        return claimKey(PersistenceConstants.OUTBOX_PREFIX + messageId);
    }

    private Mono<OutboxEntry> claimKey(String partitionKey) {
        return Mono.defer(() -> {
            String owner = UUID.randomUUID().toString();
            long now = clock.millis();
            long until = now + PersistenceConstants.OUTBOX_LEASE_MILLIS;
            return Mono
                .fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                    .key(key(partitionKey)).conditionExpression(PersistenceConstants.OUTBOX_LEASE_EXPIRED)
                    .updateExpression(PersistenceConstants.ACQUIRE_OUTBOX_LEASE)
                    .expressionAttributeValues(Map.of(PersistenceConstants.NOW_VALUE, number(now),
                        PersistenceConstants.UNTIL_VALUE, number(until), PersistenceConstants.OWNER_VALUE,
                        text(owner), PersistenceConstants.DUE_VALUE, text(time(until))))
                    .returnValues(ReturnValue.ALL_NEW).build()))
                .map(result -> new OutboxEntry(partitionKey, owner,
                    decode(result.attributes(), Envelope.class)))
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
        });
    }

    @Override
    public Mono<Boolean> pending(String messageId) {
        return get(PersistenceConstants.OUTBOX_PREFIX + messageId).hasElement();
    }

    @Override
    public Mono<Void> release(OutboxEntry entry) {
        return Mono
            .fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                .key(key(entry.key())).conditionExpression(PersistenceConstants.LEASE_OWNED)
                .updateExpression(PersistenceConstants.RELEASE_OUTBOX_LEASE)
                .expressionAttributeValues(Map.of(PersistenceConstants.OWNER_VALUE, text(entry.leaseOwner()),
                    PersistenceConstants.UNTIL_VALUE, number(0), PersistenceConstants.DUE_VALUE,
                    text(time(clock.millis()))))
                .build()))
            .then().onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }

    @Override
    public Mono<Void> acknowledge(OutboxEntry entry) {
        return Mono
            .fromFuture(
                () -> client.deleteItem(DeleteItemRequest.builder().tableName(table).key(key(entry.key()))
                    .conditionExpression(PersistenceConstants.LEASE_OWNED)
                    .expressionAttributeValues(
                        Map.of(PersistenceConstants.OWNER_VALUE, text(entry.leaseOwner())))
                    .build()))
            .then().onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }
}
