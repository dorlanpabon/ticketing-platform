package com.ticketing.inventory.dynamodb.helper;

import java.time.Duration;
import java.time.Instant;
import com.ticketing.inventory.model.WorkLease;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static com.ticketing.inventory.dynamodb.DynamoItems.key;
import static com.ticketing.inventory.dynamodb.DynamoItems.n;
import static com.ticketing.inventory.dynamodb.DynamoItems.number;
import static com.ticketing.inventory.dynamodb.DynamoItems.s;

public abstract class TemplateAdapterOperations {
    protected final DynamoDbAsyncClient client;
    protected final String table;

    protected TemplateAdapterOperations(DynamoDbAsyncClient client, String table) {
        this.client = client;
        this.table = table;
    }

    public Mono<Map<String, AttributeValue>> get(String pk, String sk) {
        return Mono.fromFuture(() -> client.getItem(GetItemRequest.builder().tableName(table)
                        .key(key(pk, sk)).consistentRead(true).build()))
                .flatMap(response -> response.hasItem() && !response.item().isEmpty()
                        ? Mono.just(response.item()) : Mono.empty());
    }

    protected Mono<Void> transact(List<TransactWriteItem> writes) {
        return transact(writes, () -> { });
    }

    protected Mono<Void> transact(List<TransactWriteItem> writes, Runnable beforeAttempt) {
        return Mono.defer(() -> {
            String token = UUID.randomUUID().toString();
            return Mono.defer(() -> {
                beforeAttempt.run();
                return Mono.fromFuture(() -> client.transactWriteItems(TransactWriteItemsRequest.builder()
                            .transactItems(writes).clientRequestToken(token).build()));
            })
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(25)).maxBackoff(Duration.ofMillis(150))
                            .jitter(0.5).filter(this::transientFailure)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure())).then();
        });
    }

    protected boolean transientFailure(Throwable error) {
        if (error instanceof TransactionCanceledException canceled) {
            return canceled.cancellationReasons().stream().anyMatch(reason ->
                    "TransactionConflict".equals(reason.code()) || "ProvisionedThroughputExceeded".equals(reason.code())
                            || "ThrottlingError".equals(reason.code()));
        }
        return error instanceof DynamoDbException service && (service.isThrottlingException() || service.statusCode() >= 500);
    }

    protected Mono<WorkLease> coordinate(String name, String owner, Instant now, long leaseSeconds, long cooldownSeconds) {
        Instant until = now.plusSeconds(leaseSeconds);
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table).key(key(name, "META"))
                .conditionExpression("(attribute_not_exists(leaseUntil) OR leaseUntil <= :now OR leaseId = :owner) AND (attribute_not_exists(nextRunAt) OR nextRunAt <= :now)")
                .updateExpression("SET leaseUntil = :until, leaseId = :owner, nextRunAt = :next")
                .expressionAttributeValues(Map.of(":now", n(now.toEpochMilli()), ":until", n(until.toEpochMilli()),
                        ":owner", s(owner), ":next", n(now.plusSeconds(cooldownSeconds).toEpochMilli()))).build()))
                .thenReturn(new WorkLease(true, until))
                .onErrorResume(ConditionalCheckFailedException.class, ignored -> get(name, "META")
                        .map(row -> new WorkLease(false, Instant.ofEpochMilli(Math.max(number(row, "leaseUntil"), number(row, "nextRunAt")))))
                        .defaultIfEmpty(new WorkLease(false, now.plusMillis(500))));
    }

    protected Mono<Void> release(String name, String owner) {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table).key(key(name, "META"))
                .conditionExpression("leaseId = :owner").updateExpression("SET leaseUntil = :zero REMOVE leaseId")
                .expressionAttributeValues(Map.of(":owner", s(owner), ":zero", n(0))).build())).then()
                .onErrorResume(ConditionalCheckFailedException.class, ignored -> Mono.empty());
    }
}
