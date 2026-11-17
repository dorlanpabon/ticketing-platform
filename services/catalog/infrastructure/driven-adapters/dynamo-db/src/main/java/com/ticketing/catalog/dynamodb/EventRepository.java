package com.ticketing.catalog.dynamodb;

import com.ticketing.catalog.dynamodb.utils.PersistenceConstants;

import com.ticketing.catalog.model.CatalogErrorCodes;
import com.ticketing.catalog.model.DomainFailure;
import com.ticketing.catalog.model.Envelope;
import com.ticketing.catalog.model.Event;
import com.ticketing.catalog.model.EventPage;
import com.ticketing.catalog.model.gateways.CatalogPort;

import org.reactivecommons.utils.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Persists catalog events, idempotency records and provisioning messages in DynamoDB.
 */
@Repository
public final class EventRepository extends OutboxTable implements CatalogPort {
    public EventRepository(DynamoDbAsyncClient client,
        @Value("${DYNAMODB_TABLE_NAME:ticketing-local-catalog}") String table, Clock clock,
        ObjectMapper mapper) {
        super(client, table, clock, mapper);
    }

    @Override
    public Mono<Event> create(Event event, String key, String fingerprint, Envelope message) {
        return previous(key, fingerprint).flatMap(this::find)
            .switchIfEmpty(Mono.defer(() -> transact(List.of(
                put(item(PersistenceConstants.EVENT_PREFIX + event.eventId(), event),
                    PersistenceConstants.ATTRIBUTE_NOT_EXISTS),
                idempotency(key, fingerprint, event.eventId()), outbox(message))).thenReturn(event)
                .onErrorResume(TransactionCanceledException.class, error -> previous(key, fingerprint)
                    .flatMap(this::find).switchIfEmpty(Mono.error(error)))));
    }

    @Override
    public Mono<Event> find(String eventId) {
        return get(PersistenceConstants.EVENT_PREFIX + eventId).map(row -> decode(row, Event.class));
    }

    @Override
    public Mono<EventPage> list(int limit, String cursor) {
        return Mono.defer(() -> {
            QueryRequest.Builder query = QueryRequest.builder().tableName(table)
                .indexName(PersistenceConstants.ACTIVE_EVENTS_INDEX_NAME)
                .keyConditionExpression(PersistenceConstants.ACTIVE_EVENTS_CONDITION)
                .expressionAttributeValues(Map.of(PersistenceConstants.EVENTS_PARAMETER,
                    text(PersistenceConstants.ACTIVE_EVENTS_PARTITION)))
                .limit(limit);
            if (cursor != null && !cursor.isBlank()) {
                try {
                    String[] values = new String(Base64.getUrlDecoder().decode(cursor),
                        StandardCharsets.UTF_8).split(PersistenceConstants.CURSOR_SEPARATOR, -1);
                    if (values.length != 2 || values[0].length() > 160 || values[1].length() > 200)
                        throw new IllegalArgumentException(PersistenceConstants.INVALID_CURSOR_MESSAGE);
                    query.exclusiveStartKey(Map.of(PersistenceConstants.PARTITION_KEY, text(values[0]),
                        PersistenceConstants.SORT_KEY, text(PersistenceConstants.METADATA_SORT_KEY),
                        PersistenceConstants.ACTIVE_EVENTS_PARTITION_KEY,
                        text(PersistenceConstants.ACTIVE_EVENTS_PARTITION),
                        PersistenceConstants.ACTIVE_EVENTS_SORT_KEY, text(values[1])));
                } catch (IllegalArgumentException error) {
                    return Mono.error(new DomainFailure(400, CatalogErrorCodes.INVALID_CURSOR));
                }
            }
            return Mono.fromFuture(() -> client.query(query.build())).map(result -> {
                String next = result.hasLastEvaluatedKey() ? Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((result.lastEvaluatedKey().get(PersistenceConstants.PARTITION_KEY).s()
                        + PersistenceConstants.CURSOR_SEPARATOR
                        + result.lastEvaluatedKey().get(PersistenceConstants.ACTIVE_EVENTS_SORT_KEY).s())
                        .getBytes(StandardCharsets.UTF_8))
                    : null;
                return new EventPage(result.items().stream().map(row -> decode(row, Event.class)).toList(),
                    next);
            });
        });
    }

    @Override
    public Mono<Void> activate(String eventId, String messageId) {
        return find(eventId)
            .switchIfEmpty(Mono.error(new DomainFailure(404, CatalogErrorCodes.UNKNOWN_EVENT_MESSAGE)))
            .flatMap(event -> {
                if (event.status().equals(Event.ACTIVE))
                    return Mono.empty();
                var row = item(PersistenceConstants.EVENT_PREFIX + eventId, event.activate());
                row.put(PersistenceConstants.ACTIVE_EVENTS_PARTITION_KEY,
                    text(PersistenceConstants.ACTIVE_EVENTS_PARTITION));
                row.put(PersistenceConstants.ACTIVE_EVENTS_SORT_KEY,
                    text(event.startsAt() + PersistenceConstants.KEY_SEPARATOR + eventId));
                return transact(List.of(put(row, PersistenceConstants.ATTRIBUTE_EXISTS), inbox(messageId)))
                    .onErrorResume(TransactionCanceledException.class,
                        error -> find(eventId).filter(current -> current.status().equals(Event.ACTIVE))
                            .switchIfEmpty(Mono.error(error)).then());
            });
    }
}
