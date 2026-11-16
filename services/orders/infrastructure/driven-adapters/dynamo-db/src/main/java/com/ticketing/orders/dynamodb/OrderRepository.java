package com.ticketing.orders.dynamodb;

import com.ticketing.orders.dynamodb.utils.PersistenceConstants;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.OrderSubmission;
import com.ticketing.orders.model.PurchaseCommand;
import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.model.gateways.OrdersPort;

import org.reactivecommons.utils.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores orders and their outbox commands and applies inventory updates.
 */
@Repository("dynamoDBTemplateAdapter")
public final class OrderRepository extends OrderTable implements OrdersPort {
    private static final Logger LOG = LoggerFactory.getLogger(OrderRepository.class);
    private final PollingCoordinator reconciliationPolling;

    public OrderRepository(DynamoDbAsyncClient client,
        @Value("${DYNAMODB_TABLE_NAME:ticketing-local-orders}") String table, Clock clock,
        ObjectMapper mapper) {
        super(client, table, clock, mapper);
        reconciliationPolling = new PollingCoordinator(client, table, clock,
            PersistenceConstants.RECONCILIATION_COORDINATOR, 60000);
    }

    private Map<String, AttributeValue> orderItem(Order order) {
        var row = item(PersistenceConstants.ORDER_PREFIX + order.orderId(), order);
        row.put(PersistenceConstants.VERSION_ATTRIBUTE, number(order.inventoryVersion()));
        if (!Set.of(Order.COMPLETED, Order.EXPIRED, Order.REJECTED).contains(order.workflowStatus())) {
            row.put(PersistenceConstants.DUE_PARTITION_KEY, text(
                PersistenceConstants.RECONCILIATION_PREFIX + Math.floorMod(order.orderId().hashCode(), 16)));
            row.put(PersistenceConstants.DUE_SORT_KEY, text(time(clock.millis() + reviewInterval(order))));
        }
        return row;
    }

    @Override
    public Mono<OrderSubmission> create(Order order, String key, String fingerprint, Envelope message) {
        return previousSubmission(key, fingerprint).switchIfEmpty(
            Mono.defer(() -> transact(List.of(put(orderItem(order), PersistenceConstants.ROW_MUST_NOT_EXIST),
                submissionIdempotency(key, fingerprint, order.orderId(), message.messageId()),
                outbox(message))).thenReturn(new OrderSubmission(order, message.messageId()))
                .onErrorResume(TransactionCanceledException.class,
                    error -> previousSubmission(key, fingerprint).switchIfEmpty(Mono.error(error)))));
    }

    private Mono<OrderSubmission> previousSubmission(String key, String fingerprint) {
        return idempotencyRecord(key, fingerprint)
            .flatMap(row -> find(row.get(PersistenceConstants.ENTITY_ID_ATTRIBUTE).s()).flatMap(order -> {
                var messageId = row.get(PersistenceConstants.PURCHASE_MESSAGE_ID);
                return messageId == null ? linkLegacySubmission(order, key, fingerprint)
                    : Mono.just(new OrderSubmission(order, messageId.s()));
            }));
    }

    private Mono<OrderSubmission> linkLegacySubmission(Order order, String idempotencyKey,
        String fingerprint) {
        Envelope message = PurchaseCommand.from(order);
        var link = Update.builder().tableName(table)
            .key(key(PersistenceConstants.IDEMPOTENCY_PREFIX + idempotencyKey))
            .conditionExpression(PersistenceConstants.UNLINKED_SUBMISSION)
            .updateExpression(PersistenceConstants.LINK_PURCHASE_MESSAGE)
            .expressionAttributeValues(Map.of(PersistenceConstants.MESSAGE_ID_VALUE,
                text(message.messageId()), PersistenceConstants.ENTITY_ID_VALUE, text(order.orderId()),
                PersistenceConstants.FINGERPRINT_VALUE, text(fingerprint)))
            .build();
        return transact(List.of(TransactWriteItem.builder().update(link).build(), outbox(message)))
            .thenReturn(new OrderSubmission(order, message.messageId()))
            .onErrorResume(TransactionCanceledException.class,
                error -> idempotencyRecord(idempotencyKey, fingerprint)
                    .filter(row -> row.containsKey(PersistenceConstants.PURCHASE_MESSAGE_ID))
                    .flatMap(row -> find(row.get(PersistenceConstants.ENTITY_ID_ATTRIBUTE).s())
                        .map(current -> new OrderSubmission(current,
                            row.get(PersistenceConstants.PURCHASE_MESSAGE_ID).s())))
                    .switchIfEmpty(Mono.error(error)));
    }

    @Override
    public Mono<Order> find(String id) {
        return get(PersistenceConstants.ORDER_PREFIX + id).map(row -> decode(row, Order.class));
    }

    @Override
    public Mono<Void> confirm(Order order, String key, Envelope message) {
        String fingerprint = order.orderId();
        return previous(key, fingerprint).hasElement().flatMap(exists -> {
            if (exists)
                return Mono.empty();
            return get(PersistenceConstants.COMMAND_PREFIX + message.messageId()).hasElement()
                .flatMap(sent -> {
                    List<TransactWriteItem> writes = new ArrayList<>();
                    writes.add(idempotency(key, fingerprint, order.orderId()));
                    if (!sent) {
                        writes.add(put(
                            item(PersistenceConstants.COMMAND_PREFIX + message.messageId(),
                                Map.of(PersistenceConstants.ORDER_ID, order.orderId())),
                            PersistenceConstants.ROW_MUST_NOT_EXIST));
                        writes.add(outbox(message));
                    }
                    return transact(writes).onErrorResume(TransactionCanceledException.class,
                        error -> previous(key, fingerprint).hasElement().flatMap(saved -> saved ? Mono.empty()
                            : get(PersistenceConstants.COMMAND_PREFIX + message.messageId())
                                .switchIfEmpty(Mono.error(error)).flatMap(
                                    command -> recordConfirmationKey(key, fingerprint, order.orderId()))));
                });
        });
    }

    private Mono<Void> recordConfirmationKey(String key, String fingerprint, String orderId) {
        return transact(List.of(idempotency(key, fingerprint, orderId))).onErrorResume(
            TransactionCanceledException.class,
            error -> previous(key, fingerprint).switchIfEmpty(Mono.error(error)).then());
    }

    @Override
    public Mono<Void> project(Reservation state, String messageId) {
        return find(state.orderId())
            .switchIfEmpty(Mono.error(new DomainFailure(404, PersistenceConstants.UNKNOWN_ORDER_MESSAGE)))
            .flatMap(order -> {
                Order updated = order.withInventory(state);
                if (updated.equals(order))
                    return Mono.empty();
                Put update = Put.builder().tableName(table).item(orderItem(updated))
                    .conditionExpression(PersistenceConstants.PROJECTION_VERSION_MATCHES)
                    .expressionAttributeNames(
                        Map.of(PersistenceConstants.VERSION_ALIAS, PersistenceConstants.VERSION_ATTRIBUTE))
                    .expressionAttributeValues(
                        Map.of(PersistenceConstants.OLD_VERSION_VALUE, number(order.inventoryVersion())))
                    .build();
                return transact(List.of(TransactWriteItem.builder().put(update).build(), inbox(messageId)))
                    .onErrorResume(TransactionCanceledException.class,
                        error -> find(order.orderId())
                            .filter(current -> current.inventoryVersion() >= state.inventoryVersion())
                            .switchIfEmpty(Mono.error(error)).then());
            });
    }

    @Override
    public Flux<Order> due() {
        return reconciliationPolling.poll(() -> Flux.range(0, 16)
            .flatMap(shard -> dueRows(PersistenceConstants.RECONCILIATION_PREFIX + shard), 4)
            .map(row -> decode(row, Order.class)));
    }

    @Override
    public Mono<Void> reviewed(Order order) {
        return Mono
            .fromFuture(
                () -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                    .key(key(PersistenceConstants.ORDER_PREFIX + order.orderId()))
                    .conditionExpression(PersistenceConstants.REVIEW_VERSION_MATCHES)
                    .expressionAttributeNames(
                        Map.of(PersistenceConstants.VERSION_ALIAS, PersistenceConstants.VERSION_ATTRIBUTE))
                    .updateExpression(PersistenceConstants.REVIEW_SUCCEEDED)
                    .expressionAttributeValues(Map.of(PersistenceConstants.VERSION_VALUE,
                        number(order.inventoryVersion()), PersistenceConstants.DUE_VALUE,
                        text(time(clock.millis() + reviewInterval(order)))))
                    .build()))
            .then().onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }

    private static long reviewInterval(Order order) {
        return Reservation.RESERVED.equals(order.status()) ? 60000 : 5000;
    }

    @Override
    public Mono<Void> reviewFailed(Order order) {
        return get(PersistenceConstants.ORDER_PREFIX + order.orderId()).flatMap(row -> {
            long failures = row.containsKey(PersistenceConstants.REVIEW_FAILURES_ATTRIBUTE)
                ? Long.parseLong(row.get(PersistenceConstants.REVIEW_FAILURES_ATTRIBUTE).n())
                : 0;
            long delay = Math.min(60000, 5000L << Math.min(4, Math.max(0, failures)));
            return Mono
                .fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                    .key(key(PersistenceConstants.ORDER_PREFIX + order.orderId()))
                    .conditionExpression(PersistenceConstants.REVIEW_VERSION_MATCHES)
                    .expressionAttributeNames(
                        Map.of(PersistenceConstants.VERSION_ALIAS, PersistenceConstants.VERSION_ATTRIBUTE))
                    .updateExpression(PersistenceConstants.REVIEW_FAILED)
                    .expressionAttributeValues(Map.of(PersistenceConstants.VERSION_VALUE,
                        number(order.inventoryVersion()), PersistenceConstants.DUE_VALUE,
                        text(time(clock.millis() + delay)), PersistenceConstants.INCREMENT_VALUE, number(1)))
                    .build()))
                .doOnSuccess(
                    ignored -> LOG.atWarn().addKeyValue(PersistenceConstants.ORDER_ID, order.orderId())
                        .addKeyValue(PersistenceConstants.RETRY_DELAY_MILLIS, delay)
                        .log("Reconciliation retry persisted"))
                .then();
        }).onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty());
    }
}
