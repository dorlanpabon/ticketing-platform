package com.ticketing.inventory.dynamodb.helper;

import com.ticketing.inventory.dynamodb.utils.PersistenceConstants;
import static com.ticketing.inventory.dynamodb.ItemAttributes.key;
import static com.ticketing.inventory.dynamodb.ItemAttributes.n;
import static com.ticketing.inventory.dynamodb.ItemAttributes.number;
import static com.ticketing.inventory.dynamodb.ItemAttributes.s;

import com.ticketing.inventory.model.WorkLease;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provides the shared DynamoDB operations used by the service repositories.
 */
public abstract class AbstractTableOperations {

    protected final DynamoDbAsyncClient client;
    protected final String table;

    protected AbstractTableOperations(DynamoDbAsyncClient client, String table) {
        this.client = client;
        this.table = table;
    }

    public Mono<Map<String, AttributeValue>> get(String pk, String sk) {
        return Mono
            .fromFuture(() -> client.getItem(
                GetItemRequest.builder().tableName(table).key(key(pk, sk)).consistentRead(true).build()))
            .flatMap(response -> response.hasItem() && !response.item().isEmpty() ? Mono.just(response.item())
                : Mono.empty());
    }

    protected Mono<Void> transact(List<TransactWriteItem> writes) {
        return transact(writes, () -> {
        });
    }

    protected Mono<Void> transact(List<TransactWriteItem> writes, Runnable beforeAttempt) {
        return Mono.defer(() -> {
            String token = UUID.randomUUID().toString();
            return Mono.defer(() -> {
                beforeAttempt.run();
                return Mono.fromFuture(() -> client.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(writes).clientRequestToken(token).build()));
            }).retryWhen(
                Retry.backoff(2, Duration.ofMillis(25)).maxBackoff(Duration.ofMillis(150)).jitter(0.5)
                    .filter(this::transientFailure).onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .then();
        });
    }

    protected boolean transientFailure(Throwable error) {
        if (error instanceof TransactionCanceledException canceled) {
            return canceled.cancellationReasons().stream()
                .anyMatch(reason -> PersistenceConstants.TRANSACTION_CONFLICT.equals(reason.code())
                    || PersistenceConstants.PROVISIONED_THROUGHPUT_EXCEEDED.equals(reason.code())
                    || PersistenceConstants.THROTTLING_ERROR.equals(reason.code()));
        }
        return error instanceof DynamoDbException service
            && (service.isThrottlingException() || service.statusCode() >= 500);
    }

    protected Mono<WorkLease> coordinate(String name, String owner, Instant now, long leaseSeconds,
        long cooldownSeconds) {
        Instant until = now.plusSeconds(leaseSeconds);
        return Mono
            .fromFuture(() -> client.updateItem(
                UpdateItemRequest.builder().tableName(table).key(key(name, PersistenceConstants.META))
                    .conditionExpression(PersistenceConstants.COORDINATOR_AVAILABLE_CONDITION)
                    .updateExpression(PersistenceConstants.CLAIM_COORDINATOR_UPDATE)
                    .expressionAttributeValues(Map.of(PersistenceConstants.NOW_VALUE, n(now.toEpochMilli()),
                        PersistenceConstants.UNTIL_VALUE, n(until.toEpochMilli()),
                        PersistenceConstants.OWNER_VALUE, s(owner), PersistenceConstants.NEXT_VALUE,
                        n(now.plusSeconds(cooldownSeconds).toEpochMilli())))
                    .build()))
            .thenReturn(new WorkLease(true, until)).onErrorResume(ConditionalCheckFailedException.class,
                ignored -> get(name, PersistenceConstants.META)
                    .map(row -> new WorkLease(false,
                        Instant.ofEpochMilli(Math.max(number(row, PersistenceConstants.LEASE_UNTIL),
                            number(row, PersistenceConstants.NEXT_RUN_AT)))))
                    .defaultIfEmpty(new WorkLease(false, now.plusMillis(500))));
    }

    protected Mono<Void> release(String name, String owner) {
        return Mono
            .fromFuture(() -> client.updateItem(
                UpdateItemRequest.builder().tableName(table).key(key(name, PersistenceConstants.META))
                    .conditionExpression(PersistenceConstants.COORDINATOR_OWNER_CONDITION)
                    .updateExpression(PersistenceConstants.RELEASE_COORDINATOR_UPDATE)
                    .expressionAttributeValues(Map.of(PersistenceConstants.OWNER_VALUE, s(owner),
                        PersistenceConstants.ZERO_VALUE, n(0)))
                    .build()))
            .then().onErrorResume(ConditionalCheckFailedException.class, ignored -> Mono.empty());
    }
}
