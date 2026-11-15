package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.model.OutboxMessage;
import com.ticketing.inventory.model.OutboxStore;
import com.ticketing.inventory.model.WorkLease;
import com.ticketing.inventory.dynamodb.helper.TemplateAdapterOperations;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static com.ticketing.inventory.dynamodb.DynamoItems.*;

@Repository
public final class DynamoOutboxStore extends TemplateAdapterOperations implements OutboxStore {
    private final java.util.concurrent.atomic.AtomicReference<LeaseSnapshot> dispatchLease = new java.util.concurrent.atomic.AtomicReference<>();

    public DynamoOutboxStore(DynamoDbAsyncClient client, @Value("${inventory.table}") String table) {
        super(client, table);
    }

    @Override public Mono<WorkLease> coordinate(String owner, Instant now) {
        return Mono.defer(() -> {
            var cached = dispatchLease.get();
            if (cached != null && cached.owner().equals(owner) && now.isBefore(cached.renewAt())) return Mono.just(cached.lease());
            return coordinate("COORDINATOR#OUTBOX", owner, now, 30, 0).doOnNext(lease -> {
                if (lease.acquired()) dispatchLease.set(new LeaseSnapshot(owner, now.plusSeconds(10), lease));
            });
        });
    }

    private record LeaseSnapshot(String owner, Instant renewAt, WorkLease lease) { }

    @Override public Flux<OutboxMessage> pending(Instant now, int limit) {
        return Flux.range(0, 16).flatMap(shard -> Mono.fromFuture(() -> client.query(QueryRequest.builder()
                        .tableName(table).indexName("gsi1").keyConditionExpression("gsi1pk = :pk AND gsi1sk <= :time")
                        .expressionAttributeValues(Map.of(":pk", s("OUTBOX#" + shard), ":time", s(time(now))))
                        .limit(limit).build())), 4)
                .flatMapIterable(QueryResponse::items).take(limit)
                .map(row -> new OutboxMessage(string(row, "pk"), string(row, "destination"), string(row, "body"), null));
    }

    @Override public Mono<OutboxMessage> claim(OutboxMessage message, Instant now) {
        String lease = UUID.randomUUID().toString();
        var until = now.plusSeconds(30);
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                        .key(key(message.id(), "META"))
                        .conditionExpression("attribute_exists(gsi1pk) AND leaseUntil <= :now")
                        .updateExpression("SET leaseUntil = :until, leaseId = :lease, gsi1sk = :due")
                        .expressionAttributeValues(Map.of(":now", n(now.toEpochMilli()), ":until", n(until.toEpochMilli()),
                                ":lease", s(lease), ":due", s(time(until)))).build()))
                .thenReturn(new OutboxMessage(message.id(), message.destination(), message.body(), lease))
                .onErrorResume(ConditionalCheckFailedException.class, ignored -> Mono.empty());
    }

    @Override public Mono<Void> sent(OutboxMessage message, Instant now) {
        return Mono.fromFuture(() -> client.updateItem(UpdateItemRequest.builder().tableName(table)
                .key(key(message.id(), "META")).conditionExpression("leaseId = :lease")
                .updateExpression("SET sentAt = :now, #ttl = :ttl REMOVE gsi1pk, gsi1sk, leaseId")
                .expressionAttributeNames(Map.of("#ttl", "ttl"))
                .expressionAttributeValues(Map.of(":lease", s(message.leaseId()), ":now", s(now.toString()),
                        ":ttl", n(now.plus(Duration.ofDays(30)).getEpochSecond()))).build())).then();
    }
}
