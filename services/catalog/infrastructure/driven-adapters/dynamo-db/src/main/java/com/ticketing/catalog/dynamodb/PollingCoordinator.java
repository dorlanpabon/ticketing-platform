package com.ticketing.catalog.dynamodb;

import com.ticketing.catalog.dynamodb.utils.PersistenceConstants;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Coordinates polling only; individual record conditions still protect every effect. */
final class PollingCoordinator {
    private final DynamoDbAsyncClient client;
    private final String table;
    private final Clock clock;
    private final String name;
    private final long maxIdleMillis;
    private final String owner = UUID.randomUUID().toString();
    private final AtomicLong leaseUntil = new AtomicLong();
    private final AtomicLong nextPoll = new AtomicLong();
    private final AtomicLong idleMillis = new AtomicLong(500);
    private final AtomicBoolean active = new AtomicBoolean();

    PollingCoordinator(DynamoDbAsyncClient client, String table, Clock clock, String name,
        long maxIdleMillis) {
        this.client = client;
        this.table = table;
        this.clock = clock;
        this.name = name;
        this.maxIdleMillis = maxIdleMillis;
    }

    <T> Flux<T> poll(Supplier<Flux<T>> work) {
        return Flux.defer(() -> {
            if (!active.compareAndSet(false, true))
                return Flux.empty();
            Flux<T> turn = clock.millis() < nextPoll.get() ? renewWhileIdle().thenMany(Flux.empty())
                : acquire().flatMapMany(owned -> owned ? batch(work) : Flux.empty());
            return turn.doOnError(error -> nextPoll.set(clock.millis() + 1000))
                .doFinally(signal -> active.set(false));
        });
    }

    private Mono<Void> renewWhileIdle() {
        return leaseUntil.get() > 0 && leaseUntil.get() <= clock.millis() + 10000 ? acquire().then()
            : Mono.empty();
    }

    private Mono<Boolean> acquire() {
        return Mono.defer(() -> {
            long now = clock.millis();
            if (leaseUntil.get() > now + 10000)
                return Mono.just(true);
            long until = now + 30000;
            var request = UpdateItemRequest.builder().tableName(table)
                .key(Map.of(PersistenceConstants.PARTITION_KEY,
                    AttributeValue.fromS(PersistenceConstants.COORDINATOR_PREFIX + name),
                    PersistenceConstants.SORT_KEY,
                    AttributeValue.fromS(PersistenceConstants.METADATA_SORT_KEY)))
                .conditionExpression(PersistenceConstants.AVAILABLE_COORDINATOR_LEASE)
                .updateExpression(PersistenceConstants.CLAIM_COORDINATOR_LEASE)
                .expressionAttributeValues(
                    Map.of(PersistenceConstants.NOW_PARAMETER, AttributeValue.fromN(Long.toString(now)),
                        PersistenceConstants.UNTIL_PARAMETER, AttributeValue.fromN(Long.toString(until)),
                        PersistenceConstants.OWNER_PARAMETER, AttributeValue.fromS(owner)))
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD).build();
            return Mono.fromFuture(() -> client.updateItem(request))
                .doOnNext(response -> leaseUntil.set(until)).thenReturn(true)
                .onErrorResume(ConditionalCheckFailedException.class, error -> {
                    leaseUntil.set(0);
                    var previous = error.item().get(PersistenceConstants.LEASE_UNTIL);
                    long retry = previous == null ? until : Long.parseLong(previous.n());
                    nextPoll.set(Math.max(now + 500, retry) + jitter());
                    return Mono.just(false);
                });
        });
    }

    private <T> Flux<T> batch(Supplier<Flux<T>> work) {
        var found = new AtomicBoolean();
        return Flux.defer(work).doOnNext(value -> found.set(true)).doOnComplete(() -> {
            long interval = found.get() ? 500 : Math.min(maxIdleMillis, idleMillis.get() * 2);
            idleMillis.set(interval);
            nextPoll.set(clock.millis() + interval + jitter());
        });
    }

    private static long jitter() {
        return ThreadLocalRandom.current().nextLong(251);
    }
}
