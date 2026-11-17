package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.dynamodb.utils.PersistenceConstants;
import static com.ticketing.inventory.dynamodb.ItemAttributes.key;
import static com.ticketing.inventory.dynamodb.ItemAttributes.n;
import static com.ticketing.inventory.dynamodb.ItemAttributes.s;
import static com.ticketing.inventory.dynamodb.ItemAttributes.string;
import static com.ticketing.inventory.dynamodb.ItemAttributes.time;

import com.ticketing.inventory.dynamodb.helper.AbstractTableOperations;
import com.ticketing.inventory.model.OutboxMessage;
import com.ticketing.inventory.model.OutboxStore;
import com.ticketing.inventory.model.WorkLease;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claims pending inventory messages and records the result of each publication.
 */
@Repository
public final class OutboxRepository extends AbstractTableOperations implements OutboxStore {

    private final AtomicReference<LeaseSnapshot> dispatchLease = new AtomicReference<>();

    public OutboxRepository(DynamoDbAsyncClient client, @Value("${inventory.table}") String table) {
        super(client, table);
    }

    @Override
    public Mono<WorkLease> coordinate(String owner, Instant now) {
        return Mono.defer(() -> {
            var cached = dispatchLease.get();
            if (cached != null && cached.owner().equals(owner) && now.isBefore(cached.renewAt()))
                return Mono.just(cached.lease());
            return coordinate(PersistenceConstants.OUTBOX_COORDINATOR, owner, now, 30, 0).doOnNext(lease -> {
                if (lease.acquired())
                    dispatchLease.set(new LeaseSnapshot(owner, now.plusSeconds(10), lease));
            });
        });
    }

    private record LeaseSnapshot(String owner, Instant renewAt, WorkLease lease) {
    }

    @Override
    public Flux<OutboxMessage> pending(Instant now, int limit) {
        return Flux.range(0, 16).flatMap(shard -> Mono.fromFuture(() -> client.query(QueryRequest.builder()
            .tableName(table).indexName(PersistenceConstants.OUTBOX_INDEX)
            .keyConditionExpression(PersistenceConstants.DUE_OUTBOX_QUERY)
            .expressionAttributeValues(Map.of(PersistenceConstants.PARTITION_KEY_VALUE,
                s(PersistenceConstants.OUTBOX_PREFIX + shard), PersistenceConstants.TIME_VALUE, s(time(now))))
            .limit(limit).build())), 4).flatMapIterable(QueryResponse::items).take(limit)
            .map(row -> new OutboxMessage(string(row, PersistenceConstants.PARTITION_KEY),
                string(row, PersistenceConstants.DESTINATION), string(row, PersistenceConstants.BODY), null));
    }

    @Override
    public Mono<OutboxMessage> claim(OutboxMessage message, Instant now) {
        String lease = UUID.randomUUID().toString();
        var until = now.plusSeconds(30);
        return Mono
            .fromFuture(() -> client.updateItem(
                UpdateItemRequest.builder().tableName(table).key(key(message.id(), PersistenceConstants.META))
                    .conditionExpression(PersistenceConstants.OUTBOX_AVAILABLE_CONDITION)
                    .updateExpression(PersistenceConstants.CLAIM_OUTBOX_UPDATE)
                    .expressionAttributeValues(Map.of(PersistenceConstants.NOW_VALUE, n(now.toEpochMilli()),
                        PersistenceConstants.UNTIL_VALUE, n(until.toEpochMilli()),
                        PersistenceConstants.LEASE_VALUE, s(lease), PersistenceConstants.DUE_VALUE,
                        s(time(until))))
                    .build()))
            .thenReturn(new OutboxMessage(message.id(), message.destination(), message.body(), lease))
            .onErrorResume(ConditionalCheckFailedException.class, ignored -> Mono.empty());
    }

    @Override
    public Mono<Void> sent(OutboxMessage message, Instant now) {
        return Mono.fromFuture(() -> client.updateItem(
            UpdateItemRequest.builder().tableName(table).key(key(message.id(), PersistenceConstants.META))
                .conditionExpression(PersistenceConstants.OUTBOX_OWNER_CONDITION)
                .updateExpression(PersistenceConstants.MARK_OUTBOX_SENT_UPDATE)
                .expressionAttributeNames(Map.of(PersistenceConstants.TTL_ALIAS, PersistenceConstants.TTL))
                .expressionAttributeValues(Map.of(PersistenceConstants.LEASE_VALUE, s(message.leaseId()),
                    PersistenceConstants.NOW_VALUE, s(now.toString()), PersistenceConstants.TTL_VALUE,
                    n(now.plus(Duration.ofDays(30)).getEpochSecond())))
                .build()))
            .then();
    }
}
