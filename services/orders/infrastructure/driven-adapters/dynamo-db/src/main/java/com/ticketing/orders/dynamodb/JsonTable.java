package com.ticketing.orders.dynamodb;

import com.ticketing.orders.model.*;
import com.ticketing.orders.model.gateways.OutboxPort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.util.*;

public abstract class JsonTable extends com.ticketing.orders.dynamodb.helper.TemplateAdapterOperations<ModelEntity,String,ModelEntity> implements OutboxPort {
    protected final DynamoDbAsyncClient client;
    protected final String table;
    protected final Clock clock;
    private final PollingCoordinator outboxPolling;
    protected final JsonMapper mapper=JsonMapper.builder().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    protected JsonTable(DynamoDbAsyncClient client,String table,Clock clock,org.reactivecommons.utils.ObjectMapper objectMapper) {
        super(software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build(),
                objectMapper, java.util.function.Function.identity(), ModelEntity.class, table, "gsi1");
        this.client=client; this.table=table; this.clock=clock;
        this.outboxPolling = new PollingCoordinator(client, table, clock, "OUTBOX", 10000);
    }
    protected static AttributeValue text(String value) { return AttributeValue.fromS(value); }
    protected static AttributeValue number(long value) { return AttributeValue.fromN(Long.toString(value)); }
    protected static Map<String,AttributeValue> key(String key) { return Map.of("pk",text(key),"sk",text("META")); }
    protected Mono<Map<String,AttributeValue>> get(String id) {
        return getDocument(client,table,key(id));
    }
    protected <T> T decode(Map<String,AttributeValue> item,Class<T> type) { return mapper.readValue(item.get("data").s(),type); }
    protected Map<String,AttributeValue> item(String id,Object data) {
        return new ModelEntity(id,mapper.writeValueAsString(data)).attributes();
    }
    protected TransactWriteItem put(Map<String,AttributeValue> item,String condition) {
        return TransactWriteItem.builder().put(Put.builder().tableName(table).item(item).conditionExpression(condition).build()).build();
    }
    protected Mono<Void> transact(List<TransactWriteItem> writes) {
        return saveTransaction(client,writes);
    }
    protected TransactWriteItem outbox(Envelope message) {
        Map<String,AttributeValue> row=item("OUTBOX#"+message.messageId(),message);
        row.put("gsi1pk",text("OUTBOX#"+Math.floorMod(message.messageId().hashCode(),16)));
        row.put("gsi1sk",text(time(clock.millis()))); row.put("leaseUntil",number(0));
        return put(row,"attribute_not_exists(pk)");
    }
    protected TransactWriteItem idempotency(String key,String fingerprint,String id) {
        Map<String,AttributeValue> row=new HashMap<>(key("IDEMP#"+key));
        row.put("fingerprint",text(fingerprint)); row.put("entityId",text(id)); row.put("ttl",number(clock.instant().plusSeconds(2592000).getEpochSecond()));
        return put(row,"attribute_not_exists(pk)");
    }
    protected Mono<String> previous(String key,String fingerprint) {
        return get("IDEMP#"+key).flatMap(row -> row.get("fingerprint").s().equals(fingerprint)
                ? Mono.just(row.get("entityId").s()) : Mono.error(new DomainFailure(409,"IDEMPOTENCY_CONFLICT")));
    }
    protected TransactWriteItem inbox(String id) {
        Map<String,AttributeValue> row=new HashMap<>(key("INBOX#"+id)); row.put("ttl",number(clock.instant().plusSeconds(2592000).getEpochSecond()));
        return put(row,"attribute_not_exists(pk)");
    }
    protected static String time(long millis) { return String.format(Locale.ROOT,"%016d",millis); }
    protected Flux<Map<String,AttributeValue>> dueRows(String partition) {
        return Mono.fromFuture(() -> client.query(QueryRequest.builder().tableName(table).indexName("gsi1")
                .keyConditionExpression("gsi1pk = :partition AND gsi1sk <= :now")
                .expressionAttributeValues(Map.of(":partition",text(partition),":now",text(time(clock.millis())))).limit(25).build()))
                .flatMapMany(result -> Flux.fromIterable(result.items()));
    }
    @Override public Flux<OutboxEntry> claim() {
        return outboxPolling.poll(this::claimPending);
    }
    private Flux<OutboxEntry> claimPending() {
        return Flux.range(0,16).flatMap(shard -> dueRows("OUTBOX#"+shard),4)
                .flatMap(row -> {
                    String owner=UUID.randomUUID().toString(); long now=clock.millis();
                    return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table).key(key(row.get("pk").s()))
                            .conditionExpression("attribute_exists(pk) AND leaseUntil < :now")
                            .updateExpression("SET leaseUntil = :until, leaseOwner = :owner, gsi1sk = :due")
                            .expressionAttributeValues(Map.of(":now",number(now),":until",number(now+30000),":owner",text(owner),":due",text(time(now+30000))))
                            .returnValues(ReturnValue.ALL_NEW).build()))
                            .map(result -> new OutboxEntry(row.get("pk").s(),owner,decode(result.attributes(),Envelope.class)))
                            .onErrorResume(ConditionalCheckFailedException.class,error -> Mono.empty());
                },8);
    }
    @Override public Mono<Void> acknowledge(OutboxEntry entry) {
        return Mono.fromFuture(() -> client.deleteItem(DeleteItemRequest.builder().tableName(table).key(key(entry.key()))
                .conditionExpression("leaseOwner = :owner").expressionAttributeValues(Map.of(":owner",text(entry.leaseOwner()))).build()))
                .then().onErrorResume(ConditionalCheckFailedException.class,error -> Mono.empty());
    }
}
