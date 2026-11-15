package com.ticketing.orders.dynamodb;

import com.ticketing.orders.model.*;
import com.ticketing.orders.model.gateways.OrdersPort;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.Clock;
import java.util.*;
@Repository
public final class DynamoDBTemplateAdapter extends JsonTable implements OrdersPort {
    private static final System.Logger LOG = System.getLogger(DynamoDBTemplateAdapter.class.getName());
    private static final String ORDER_PREFIX = "ORDER#";
    private static final String VERSION_ATTRIBUTE = "version";
    private final PollingCoordinator reconciliationPolling;
    public DynamoDBTemplateAdapter(DynamoDbAsyncClient client,@Value("${DYNAMODB_TABLE_NAME:ticketing-local-orders}") String table,Clock clock,org.reactivecommons.utils.ObjectMapper mapper) {
        super(client,table,clock,mapper);
        reconciliationPolling = new PollingCoordinator(client,table,clock,"RECONCILIATION",60000);
    }
    private Map<String,AttributeValue> orderItem(Order order) {
        var row=item(ORDER_PREFIX+order.orderId(),order); row.put(VERSION_ATTRIBUTE,number(order.inventoryVersion()));
        if(!Set.of("COMPLETED","EXPIRED","REJECTED").contains(order.workflowStatus())) {
            row.put("gsi1pk",text("RECONCILE#"+Math.floorMod(order.orderId().hashCode(),16)));
            row.put("gsi1sk",text(time(clock.millis()+reviewInterval(order))));
        }
        return row;
    }
    @Override public Mono<Order> create(Order order,String key,String fingerprint,Envelope message) {
        return previous(key,fingerprint).flatMap(this::find).switchIfEmpty(Mono.defer(() ->
                transact(List.of(put(orderItem(order),"attribute_not_exists(pk)"),idempotency(key,fingerprint,order.orderId()),outbox(message)))
                        .thenReturn(order).onErrorResume(TransactionCanceledException.class,error -> previous(key,fingerprint).flatMap(this::find).switchIfEmpty(Mono.error(error)))));
    }
    @Override public Mono<Order> find(String id) { return get(ORDER_PREFIX+id).map(row -> decode(row,Order.class)); }
    @Override public Mono<Void> confirm(Order order,String key,Envelope message) {
        String fingerprint=order.orderId();
        return previous(key,fingerprint).hasElement().flatMap(exists -> {
            if(exists) return Mono.empty();
            return get("COMMAND#"+message.messageId()).hasElement().flatMap(sent -> {
                List<TransactWriteItem> writes=new ArrayList<>(); writes.add(idempotency(key,fingerprint,order.orderId()));
                if(!sent) { writes.add(put(item("COMMAND#"+message.messageId(),Map.of("orderId",order.orderId())),"attribute_not_exists(pk)")); writes.add(outbox(message)); }
                return transact(writes).onErrorResume(TransactionCanceledException.class,error ->
                        previous(key,fingerprint).hasElement().flatMap(saved -> saved ? Mono.empty()
                                : get("COMMAND#"+message.messageId()).switchIfEmpty(Mono.error(error))
                                        .flatMap(command -> recordConfirmationKey(key,fingerprint,order.orderId()))));
            });
        });
    }
    private Mono<Void> recordConfirmationKey(String key,String fingerprint,String orderId) {
        return transact(List.of(idempotency(key,fingerprint,orderId)))
                .onErrorResume(TransactionCanceledException.class,error -> previous(key,fingerprint)
                        .switchIfEmpty(Mono.error(error)).then());
    }
    @Override public Mono<Void> project(Reservation state,String messageId) {
        return find(state.orderId()).switchIfEmpty(Mono.error(new DomainFailure(404,"UNKNOWN_ORDER_MESSAGE"))).flatMap(order -> {
            Order updated=order.withInventory(state);
            if(updated.equals(order)) return Mono.empty();
            Put update=Put.builder().tableName(table).item(orderItem(updated)).conditionExpression("#version = :old")
                    .expressionAttributeNames(Map.of("#version",VERSION_ATTRIBUTE)).expressionAttributeValues(Map.of(":old",number(order.inventoryVersion()))).build();
            return transact(List.of(TransactWriteItem.builder().put(update).build(),inbox(messageId)))
                    .onErrorResume(TransactionCanceledException.class,error -> find(order.orderId()).filter(current -> current.inventoryVersion()>=state.inventoryVersion()).switchIfEmpty(Mono.error(error)).then());
        });
    }
    @Override public Flux<Order> due() {
        return reconciliationPolling.poll(() -> Flux.range(0,16)
                .flatMap(shard -> dueRows("RECONCILE#"+shard),4).map(row -> decode(row,Order.class)));
    }
    @Override public Mono<Void> reviewed(Order order) {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table).key(key(ORDER_PREFIX+order.orderId()))
                .conditionExpression("#version = :version AND attribute_exists(gsi1pk)").expressionAttributeNames(Map.of("#version",VERSION_ATTRIBUTE))
                .updateExpression("SET gsi1sk = :due REMOVE reviewFailures").expressionAttributeValues(Map.of(":version",number(order.inventoryVersion()),":due",text(time(clock.millis()+reviewInterval(order))))).build()))
                .then().onErrorResume(ConditionalCheckFailedException.class,error -> Mono.empty());
    }

    private static long reviewInterval(Order order) {
        return "RESERVED".equals(order.status()) ? 60000 : 5000;
    }

    @Override public Mono<Void> reviewFailed(Order order) {
        return get(ORDER_PREFIX+order.orderId()).flatMap(row -> {
            long failures = row.containsKey("reviewFailures") ? Long.parseLong(row.get("reviewFailures").n()) : 0;
            long delay = Math.min(60000, 5000L << Math.min(4, Math.max(0, failures)));
            return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                    .key(key(ORDER_PREFIX+order.orderId()))
                    .conditionExpression("#version = :version AND attribute_exists(gsi1pk)")
                    .expressionAttributeNames(Map.of("#version",VERSION_ATTRIBUTE))
                    .updateExpression("SET gsi1sk = :due ADD reviewFailures :one")
                    .expressionAttributeValues(Map.of(":version",number(order.inventoryVersion()),
                            ":due",text(time(clock.millis()+delay)),":one",number(1))).build()))
                    .doOnSuccess(ignored -> LOG.log(System.Logger.Level.WARNING,
                            "Reconciliation retry persisted order={0} delayMs={1}", order.orderId(), delay))
                    .then();
        }).onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }
}
