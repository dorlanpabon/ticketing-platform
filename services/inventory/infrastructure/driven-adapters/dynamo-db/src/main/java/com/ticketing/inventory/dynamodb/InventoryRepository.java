package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.dynamodb.utils.PersistenceConstants;
import static com.ticketing.inventory.dynamodb.ItemAttributes.item;
import static com.ticketing.inventory.dynamodb.ItemAttributes.key;
import static com.ticketing.inventory.dynamodb.ItemAttributes.n;
import static com.ticketing.inventory.dynamodb.ItemAttributes.number;
import static com.ticketing.inventory.dynamodb.ItemAttributes.partition;
import static com.ticketing.inventory.dynamodb.ItemAttributes.s;
import static com.ticketing.inventory.dynamodb.ItemAttributes.shard;
import static com.ticketing.inventory.dynamodb.ItemAttributes.string;
import static com.ticketing.inventory.dynamodb.ItemAttributes.ticketKey;
import static com.ticketing.inventory.dynamodb.ItemAttributes.time;

import com.ticketing.inventory.dynamodb.helper.AbstractTableOperations;
import com.ticketing.inventory.model.Availability;
import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.InventoryStore;
import com.ticketing.inventory.model.Reservation;
import com.ticketing.inventory.model.ReservationIntent;
import com.ticketing.inventory.model.Ticket;
import com.ticketing.inventory.model.TicketPage;
import com.ticketing.inventory.model.TicketStatus;
import com.ticketing.inventory.model.WorkLease;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Get;
import software.amazon.awssdk.services.dynamodb.model.ItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItem;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Persists ticket state, reservations and their availability counters atomically.
 */
@Repository
public final class InventoryRepository extends AbstractTableOperations implements InventoryStore {

    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public InventoryRepository(DynamoDbAsyncClient client, @Value("${inventory.table}") String table,
        Clock clock) {
        super(client, table);
        this.clock = clock;
    }

    @Override
    public Mono<Reservation> find(String orderId) {
        return get(PersistenceConstants.RESERVATION_PREFIX + orderId, PersistenceConstants.META)
            .map(TableEntity::fromAttributes).map(entity -> json.readValue(entity.data(), Reservation.class));
    }

    @Override
    public Flux<Reservation> holders(String eventId, List<String> ticketIds) {
        if (ticketIds.isEmpty())
            return Flux.empty();
        var reads = ticketIds.stream().distinct()
            .map(id -> TransactGetItem.builder().get(
                Get.builder().tableName(table).key(key(partition(eventId, shard(id)), ticketKey(id))).build())
                .build())
            .toList();
        return Mono
            .fromFuture(
                () -> client.transactGetItems(TransactGetItemsRequest.builder().transactItems(reads).build()))
            .flatMapMany(response -> Flux.fromIterable(response.responses())).map(ItemResponse::item)
            .filter(row -> row.containsKey(PersistenceConstants.RESERVATION_ID)
                && TicketStatus.valueOf(string(row, PersistenceConstants.STATE)).isHeld())
            .map(row -> string(row, PersistenceConstants.RESERVATION_ID)).distinct().flatMap(this::find, 8);
    }

    @Override
    public Mono<Reservation> reserve(ReservationIntent request) {
        return Mono.defer(() -> {
            var reservation = Reservation.accepted(request);
            var writes = stateRecords(reservation, PersistenceConstants.ABSENT, Map.of(), Map.of());
            writes.add(TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(table)
                .key(key(PersistenceConstants.EVENT_PREFIX + request.eventId(), PersistenceConstants.META))
                .conditionExpression(PersistenceConstants.EVENT_READY_CONDITION)
                .expressionAttributeNames(
                    Map.of(PersistenceConstants.STATE_ALIAS, PersistenceConstants.STATE))
                .expressionAttributeValues(
                    Map.of(PersistenceConstants.READY_VALUE, s(PersistenceConstants.READY)))
                .build()).build());
            for (String ticketId : request.ticketIds()) {
                writes.add(ticketTransition(request.eventId(), ticketId, TicketStatus.AVAILABLE,
                    reservation.status(), request.orderId(), request.customerId(), request.expiresAt()));
            }
            addCounters(writes, request.eventId(), request.ticketIds(), TicketStatus.AVAILABLE,
                reservation.status());
            if (reservation.status().isHeld()) {
                writes.add(put(expiryKey(reservation), PersistenceConstants.ABSENT, Map.of(), Map.of()));
                writes
                    .add(outbox(PersistenceConstants.EXPIRY_MESSAGE_PREFIX + request.orderId(),
                        PersistenceConstants.EXPIRE_RESERVATION, request.orderId(), 1,
                        Map.of(PersistenceConstants.ORDER_ID, request.orderId(),
                            PersistenceConstants.EXPIRES_AT, request.expiresAt().toString()),
                        PersistenceConstants.EXPIRATIONS_DESTINATION));
            }
            return transact(writes,
                () -> requireBeforeDeadline(request.expiresAt(), PersistenceConstants.RESERVATION_EXPIRED))
                .thenReturn(reservation).onErrorResume(TransactionCanceledException.class, error -> {
                    if (transientFailure(error))
                        return Mono.error(error);
                    String code = error.cancellationReasons().size() > 3
                        && PersistenceConstants.CONDITIONAL_CHECK_FAILED
                            .equals(error.cancellationReasons().get(3).code())
                                ? PersistenceConstants.NOT_READY
                                : PersistenceConstants.TICKETS_UNAVAILABLE;
                    return find(request.orderId()).flatMap(existing -> match(existing, request))
                        .switchIfEmpty(Mono.error(new InventoryException(code,
                            PersistenceConstants.NOT_READY.equals(code) ? 503 : 409)));
                });
        });
    }

    @Override
    public Mono<Reservation> reject(ReservationIntent request, String reason) {
        var result = Reservation.rejected(request, reason);
        return transact(stateRecords(result, PersistenceConstants.ABSENT, Map.of(), Map.of()))
            .thenReturn(result)
            .onErrorResume(TransactionCanceledException.class, error -> find(request.orderId())
                .flatMap(existing -> match(existing, request)).switchIfEmpty(Mono.error(error)));
    }

    private Mono<Reservation> match(Reservation reservation, ReservationIntent request) {
        return reservation.matches(request) ? Mono.just(reservation)
            : Mono.error(new InventoryException(PersistenceConstants.IDEMPOTENCY_CONFLICT, 409));
    }

    @Override
    public Mono<Reservation> transition(Reservation expected, TicketStatus target, String resolution,
        String commandId, Instant observedAt) {
        return Mono.defer(() -> {
            var next = expected.transition(target, resolution, commandId);
            String timeCondition = target == TicketStatus.AVAILABLE ? PersistenceConstants.AT_OR_BEFORE
                : PersistenceConstants.AFTER;
            var writes = stateRecords(next,
                PersistenceConstants.RESERVATION_STATE_CONDITION + timeCondition
                    + PersistenceConstants.NOW_VALUE,
                Map.of(PersistenceConstants.VERSION_ALIAS, PersistenceConstants.VERSION,
                    PersistenceConstants.STATE_ALIAS, PersistenceConstants.STATE),
                Map.of(PersistenceConstants.VERSION_VALUE, n(expected.inventoryVersion()),
                    PersistenceConstants.STATE_VALUE, s(expected.status().name()),
                    PersistenceConstants.NOW_VALUE, n(observedAt.toEpochMilli())));
            for (String ticketId : expected.activeTicketIds()) {
                writes.add(ticketTransition(expected.eventId(), ticketId, expected.status(), target,
                    expected.orderId(), expected.customerId(), expected.expiresAt()));
            }
            addCounters(writes, expected.eventId(), expected.activeTicketIds(), expected.status(), target);
            if (!target.isHeld())
                writes.add(TransactWriteItem.builder()
                    .delete(Delete.builder().tableName(table).key(expiryKey(expected)).build()).build());
            if (target == TicketStatus.SOLD) {
                var inbox = item(PersistenceConstants.INBOX_PREFIX + commandId, PersistenceConstants.META);
                inbox.put(PersistenceConstants.TTL,
                    n(clock.instant().plus(Duration.ofDays(30)).getEpochSecond()));
                writes.add(put(inbox, PersistenceConstants.ABSENT, Map.of(), Map.of()));
            }
            return transact(writes, () -> {
                if (target != TicketStatus.AVAILABLE)
                    requireBeforeDeadline(expected.expiresAt(), PersistenceConstants.CONCURRENT_TRANSITION);
            }).thenReturn(next).onErrorMap(TransactionCanceledException.class,
                error -> transientFailure(error) ? error
                    : new InventoryException(PersistenceConstants.CONCURRENT_TRANSITION, 409));
        });
    }

    private void requireBeforeDeadline(Instant deadline, String code) {
        if (!clock.instant().isBefore(deadline))
            throw new InventoryException(code, 409);
    }

    private Map<String, AttributeValue> expiryKey(Reservation reservation) {
        return key(
            PersistenceConstants.EVENT_EXPIRY_PREFIX + reservation.eventId()
                + PersistenceConstants.KEY_SEPARATOR + Math.floorMod(reservation.orderId().hashCode(), 16),
            time(reservation.expiresAt()) + PersistenceConstants.KEY_SEPARATOR + reservation.orderId());
    }

    private List<TransactWriteItem> stateRecords(Reservation reservation, String condition,
        Map<String, String> names, Map<String, AttributeValue> values) {
        var writes = new ArrayList<TransactWriteItem>();
        var row = new TableEntity(PersistenceConstants.RESERVATION_PREFIX + reservation.orderId(),
            PersistenceConstants.META, json.writeValueAsString(reservation)).attributes();
        row.put(PersistenceConstants.VERSION, n(reservation.inventoryVersion()));
        row.put(PersistenceConstants.STATE, s(reservation.status().name()));
        row.put(PersistenceConstants.EXPIRES_AT_MILLIS, n(reservation.expiresAt().toEpochMilli()));
        if (reservation.status().isHeld()) {
            row.put(PersistenceConstants.EXPIRY_PARTITION_KEY,
                s(PersistenceConstants.EXPIRY_PREFIX + Math.floorMod(reservation.orderId().hashCode(), 16)));
            row.put(PersistenceConstants.EXPIRY_SORT_KEY, s(time(reservation.expiresAt())));
        }
        writes.add(put(row, condition, names, values));
        var audit = item(PersistenceConstants.AUDIT_PREFIX + reservation.orderId(), String.format(Locale.ROOT,
            PersistenceConstants.ORDERED_NUMBER_FORMAT, reservation.inventoryVersion()));
        audit.put(PersistenceConstants.DATA, s(json.writeValueAsString(reservation)));
        audit.put(PersistenceConstants.OCCURRED_AT, s(clock.instant().toString()));
        writes.add(put(audit, PersistenceConstants.ABSENT, Map.of(), Map.of()));
        writes.add(outbox(
            PersistenceConstants.RESERVATION_MESSAGE_PREFIX + reservation.orderId()
                + PersistenceConstants.MESSAGE_VERSION_SEPARATOR + reservation.inventoryVersion(),
            PersistenceConstants.RESERVATION_STATE_CHANGED, reservation.orderId(),
            reservation.inventoryVersion(), reservation, PersistenceConstants.ORDERS_DESTINATION));
        return writes;
    }

    private TransactWriteItem ticketTransition(String eventId, String ticketId, TicketStatus source,
        TicketStatus target, String reservationId, String customerId, Instant expiresAt) {
        var values = new HashMap<String, AttributeValue>();
        values.put(PersistenceConstants.SOURCE_VALUE, s(source.name()));
        values.put(PersistenceConstants.TARGET_VALUE, s(target.name()));
        values.put(PersistenceConstants.RESERVATION_VALUE, s(reservationId));
        String condition = PersistenceConstants.TICKET_STATE_CONDITION;
        String update;
        if (target == TicketStatus.AVAILABLE) {
            condition += PersistenceConstants.RESERVATION_OWNER_CONDITION;
            update = PersistenceConstants.RELEASE_TICKET_UPDATE;
        } else {
            if (source != TicketStatus.AVAILABLE)
                condition += PersistenceConstants.RESERVATION_OWNER_CONDITION;
            values.put(PersistenceConstants.CUSTOMER_VALUE, s(customerId));
            values.put(PersistenceConstants.EXPIRES_VALUE, n(expiresAt.toEpochMilli()));
            update = PersistenceConstants.ASSIGN_TICKET_UPDATE;
        }
        return TransactWriteItem.builder().update(Update.builder().tableName(table)
            .key(key(partition(eventId, shard(ticketId)), ticketKey(ticketId))).conditionExpression(condition)
            .updateExpression(update)
            .expressionAttributeNames(Map.of(PersistenceConstants.STATE_ALIAS, PersistenceConstants.STATE))
            .expressionAttributeValues(values).build()).build();
    }

    private void addCounters(List<TransactWriteItem> writes, String eventId, List<String> tickets,
        TicketStatus source, TicketStatus target) {
        var counts = tickets.stream()
            .collect(Collectors.groupingBy(ItemAttributes::shard, Collectors.counting()));
        counts
            .forEach((shard,
                count) -> writes.add(TransactWriteItem.builder().update(Update.builder().tableName(table)
                    .key(key(partition(eventId, shard), PersistenceConstants.COUNTERS))
                    .conditionExpression(PersistenceConstants.COUNTER_CAPACITY_CONDITION)
                    .updateExpression(PersistenceConstants.TRANSFER_COUNTER_UPDATE)
                    .expressionAttributeNames(Map.of(PersistenceConstants.SOURCE_ALIAS, source.name(),
                        PersistenceConstants.TARGET_ALIAS, target.name()))
                    .expressionAttributeValues(Map.of(PersistenceConstants.COUNT_VALUE, n(count))).build())
                    .build()));
    }

    private TransactWriteItem put(Map<String, AttributeValue> row, String condition,
        Map<String, String> names, Map<String, AttributeValue> values) {
        var builder = Put.builder().tableName(table).item(row).conditionExpression(condition);
        if (!names.isEmpty())
            builder.expressionAttributeNames(names);
        if (!values.isEmpty())
            builder.expressionAttributeValues(values);
        return TransactWriteItem.builder().put(builder.build()).build();
    }

    private TransactWriteItem outbox(String id, String type, String aggregateId, long version, Object payload,
        String destination) {
        var now = clock.instant();
        var message = Map.of(PersistenceConstants.MESSAGE_ID, id, PersistenceConstants.TYPE, type,
            PersistenceConstants.SCHEMA_VERSION, 1, PersistenceConstants.AGGREGATE_ID, aggregateId,
            PersistenceConstants.AGGREGATE_VERSION, version, PersistenceConstants.CORRELATION_ID, aggregateId,
            PersistenceConstants.CAUSATION_ID, aggregateId, PersistenceConstants.OCCURRED_AT, now.toString(),
            PersistenceConstants.PAYLOAD, payload);
        var row = item(PersistenceConstants.OUTBOX_PREFIX + id, PersistenceConstants.META);
        row.put(PersistenceConstants.BODY, s(json.writeValueAsString(message)));
        row.put(PersistenceConstants.DESTINATION, s(destination));
        row.put(PersistenceConstants.OUTBOX_PARTITION_KEY,
            s(PersistenceConstants.OUTBOX_PREFIX + Math.floorMod(id.hashCode(), 16)));
        row.put(PersistenceConstants.OUTBOX_SORT_KEY, s(time(now)));
        row.put(PersistenceConstants.LEASE_UNTIL, n(0));
        return put(row, PersistenceConstants.ABSENT, Map.of(), Map.of());
    }

    @Override
    public Mono<Void> provision(String eventId, int capacity) {
        return initialize(eventId, capacity).thenMany(Flux.range(0, (capacity + 47) / 48))
            .concatMap(chunk -> provisionChunk(eventId, capacity, chunk))
            .then(completeProvision(eventId, capacity));
    }

    private Mono<Void> initialize(String eventId, int capacity) {
        return get(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.META)
            .flatMap(existing -> {
                if (number(existing, PersistenceConstants.CAPACITY) != capacity)
                    return Mono
                        .error(new InventoryException(PersistenceConstants.EVENT_CAPACITY_CONFLICT, 409));
                return Mono.just(true);
            }).switchIfEmpty(Mono.defer(() -> {
                var writes = new ArrayList<TransactWriteItem>();
                var event = item(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.META);
                event.put(PersistenceConstants.STATE, s(PersistenceConstants.PROVISIONING));
                event.put(PersistenceConstants.CAPACITY, n(capacity));
                event.put(PersistenceConstants.INITIALIZED, n(0));
                writes.add(put(event, PersistenceConstants.ABSENT, Map.of(), Map.of()));
                for (int shard = 0; shard < 16; shard++) {
                    var counter = item(partition(eventId, shard), PersistenceConstants.COUNTERS);
                    for (TicketStatus state : TicketStatus.values())
                        counter.put(state.name(), n(0));
                    writes.add(put(counter, PersistenceConstants.ABSENT, Map.of(), Map.of()));
                }
                return transact(writes).thenReturn(true).onErrorResume(TransactionCanceledException.class,
                    error -> initialize(eventId, capacity).thenReturn(true));
            })).then();
    }

    private Mono<Void> provisionChunk(String eventId, int capacity, int chunk) {
        return get(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.CHUNK_PREFIX + chunk)
            .map(ignored -> true).switchIfEmpty(Mono.defer(() -> {
                var writes = new ArrayList<TransactWriteItem>();
                var counts = new HashMap<Integer, Integer>();
                int first = chunk * 48 + 1, end = Math.min(capacity, first + 47);
                for (int ticket = first; ticket <= end; ticket++) {
                    String id = Integer.toString(ticket);
                    var row = item(partition(eventId, shard(id)), ticketKey(id));
                    row.put(PersistenceConstants.EVENT_ID, s(eventId));
                    row.put(PersistenceConstants.TICKET_ID, s(id));
                    row.put(PersistenceConstants.STATE, s(PersistenceConstants.AVAILABLE_STATE));
                    writes.add(put(row, PersistenceConstants.ABSENT, Map.of(), Map.of()));
                    counts.merge(shard(id), 1, Integer::sum);
                }
                counts.forEach((shard, count) -> writes.add(TransactWriteItem.builder().update(Update
                    .builder().tableName(table)
                    .key(key(partition(eventId, shard), PersistenceConstants.COUNTERS))
                    .updateExpression(PersistenceConstants.ADD_AVAILABLE_COUNTER_UPDATE)
                    .expressionAttributeNames(
                        Map.of(PersistenceConstants.AVAILABLE_ALIAS, PersistenceConstants.AVAILABLE_STATE))
                    .expressionAttributeValues(Map.of(PersistenceConstants.COUNT_VALUE, n(count))).build())
                    .build()));
                writes.add(put(
                    item(PersistenceConstants.EVENT_PREFIX + eventId,
                        PersistenceConstants.CHUNK_PREFIX + chunk),
                    PersistenceConstants.ABSENT, Map.of(), Map.of()));
                writes.add(TransactWriteItem.builder().update(Update.builder().tableName(table)
                    .key(key(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.META))
                    .conditionExpression(PersistenceConstants.EVENT_PROVISIONING_CONDITION)
                    .updateExpression(PersistenceConstants.ADD_INITIALIZED_UPDATE)
                    .expressionAttributeNames(
                        Map.of(PersistenceConstants.STATE_ALIAS, PersistenceConstants.STATE))
                    .expressionAttributeValues(
                        Map.of(PersistenceConstants.PROVISIONING_VALUE, s(PersistenceConstants.PROVISIONING),
                            PersistenceConstants.COUNT_VALUE, n(end - first + 1)))
                    .build()).build());
                return transact(writes).thenReturn(true).onErrorResume(TransactionCanceledException.class,
                    error -> get(PersistenceConstants.EVENT_PREFIX + eventId,
                        PersistenceConstants.CHUNK_PREFIX + chunk).map(ignored -> true)
                        .switchIfEmpty(Mono.error(error)));
            })).then();
    }

    private Mono<Void> completeProvision(String eventId, int capacity) {
        var writes = new ArrayList<TransactWriteItem>();
        writes.add(TransactWriteItem.builder().update(Update.builder().tableName(table)
            .key(key(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.META))
            .conditionExpression(PersistenceConstants.PROVISION_COMPLETE_CONDITION)
            .updateExpression(PersistenceConstants.MARK_EVENT_READY_UPDATE)
            .expressionAttributeNames(Map.of(PersistenceConstants.STATE_ALIAS, PersistenceConstants.STATE))
            .expressionAttributeValues(Map.of(PersistenceConstants.PROVISIONING_VALUE,
                s(PersistenceConstants.PROVISIONING), PersistenceConstants.READY_VALUE,
                s(PersistenceConstants.READY), PersistenceConstants.CAPACITY_VALUE, n(capacity)))
            .build()).build());
        writes.add(outbox(PersistenceConstants.READY_MESSAGE_PREFIX + eventId,
            PersistenceConstants.INVENTORY_READY, eventId, 1,
            Map.of(PersistenceConstants.EVENT_ID, eventId, PersistenceConstants.CAPACITY, capacity),
            PersistenceConstants.CATALOG_DESTINATION));
        return transact(writes).onErrorResume(TransactionCanceledException.class,
            error -> get(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.META)
                .filter(row -> PersistenceConstants.READY.equals(string(row, PersistenceConstants.STATE)))
                .switchIfEmpty(Mono.error(error)).then());
    }

    @Override
    public Mono<Availability> availability(String eventId) {
        var requests = new ArrayList<TransactGetItem>();
        requests.add(TransactGetItem.builder()
            .get(Get.builder().tableName(table)
                .key(key(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.META)).build())
            .build());
        for (int shard = 0; shard < 16; shard++)
            requests
                .add(
                    TransactGetItem.builder()
                        .get(Get.builder().tableName(table)
                            .key(key(partition(eventId, shard), PersistenceConstants.COUNTERS)).build())
                        .build());
        return Mono.fromFuture(
            () -> client.transactGetItems(TransactGetItemsRequest.builder().transactItems(requests).build()))
            .map(response -> {
                var metadata = response.responses().getFirst().item();
                if (metadata.isEmpty())
                    throw new InventoryException(PersistenceConstants.EVENT_NOT_FOUND, 404);
                if (!PersistenceConstants.READY.equals(string(metadata, PersistenceConstants.STATE)))
                    throw new InventoryException(PersistenceConstants.NOT_READY, 409);
                long[] totals = new long[5];
                for (int index = 1; index < response.responses().size(); index++) {
                    var row = response.responses().get(index).item();
                    for (TicketStatus state : TicketStatus.values())
                        totals[state.ordinal()] += number(row, state.name());
                }
                return new Availability(eventId, number(metadata, PersistenceConstants.CAPACITY), totals[0],
                    totals[1], totals[2], totals[3], totals[4]);
            }).flatMap(snapshot -> assertFresh(eventId).thenReturn(snapshot));
    }

    private Mono<Void> assertFresh(String eventId) {
        return Flux.range(0, 16).flatMap(shard -> Mono.fromFuture(() -> client.query(QueryRequest.builder()
            .tableName(table).consistentRead(true)
            .keyConditionExpression(PersistenceConstants.OVERDUE_EVENT_QUERY)
            .expressionAttributeValues(Map.of(PersistenceConstants.PARTITION_KEY_VALUE,
                s(PersistenceConstants.EVENT_EXPIRY_PREFIX + eventId + PersistenceConstants.KEY_SEPARATOR
                    + shard),
                PersistenceConstants.DEADLINE_VALUE, s(time(clock.instant().minusSeconds(5)))))
            .limit(1).build())), 4).any(response -> !response.items().isEmpty()).flatMap(
                stale -> stale ? Mono.error(new InventoryException(PersistenceConstants.INVENTORY_STALE, 503))
                    : Mono.empty());
    }

    @Override
    public Mono<TicketPage> tickets(String eventId, int limit, String cursor) {
        int start;
        try {
            start = cursor == null ? 1 : Integer.parseInt(cursor);
        } catch (NumberFormatException error) {
            return Mono.error(new InventoryException(PersistenceConstants.INVALID_CURSOR, 400));
        }
        if (start < 1 || start > 100_000)
            return Mono.error(new InventoryException(PersistenceConstants.INVALID_CURSOR, 400));
        return get(PersistenceConstants.EVENT_PREFIX + eventId, PersistenceConstants.META)
            .switchIfEmpty(Mono.error(new InventoryException(PersistenceConstants.EVENT_NOT_FOUND, 404)))
            .flatMap(metadata -> {
                if (!PersistenceConstants.READY.equals(string(metadata, PersistenceConstants.STATE)))
                    return Mono.error(new InventoryException(PersistenceConstants.NOT_READY, 409));
                int capacity = (int) number(metadata, PersistenceConstants.CAPACITY),
                    last = Math.min(capacity, start + limit - 1);
                if (start > capacity)
                    return Mono.just(new TicketPage(List.of(), null));
                var reads = new ArrayList<TransactGetItem>();
                for (int ticket = start; ticket <= last; ticket++) {
                    String id = Integer.toString(ticket);
                    reads.add(TransactGetItem.builder().get(Get.builder().tableName(table)
                        .key(key(partition(eventId, shard(id)), ticketKey(id))).build()).build());
                }
                return Mono
                    .fromFuture(() -> client
                        .transactGetItems(TransactGetItemsRequest.builder().transactItems(reads).build()))
                    .map(response -> new TicketPage(
                        response.responses().stream().map(ItemResponse::item)
                            .map(row -> new Ticket(eventId, string(row, PersistenceConstants.TICKET_ID),
                                TicketStatus.valueOf(string(row, PersistenceConstants.STATE))))
                            .toList(),
                        last < capacity ? Integer.toString(last + 1) : null));
            });
    }

    @Override
    public Flux<Reservation> expired(Instant now, int limit) {
        return Flux.range(0, 16)
            .flatMap(shard -> expiryPage(shard, now, limit, Map.of())
                .expand(page -> page.lastEvaluatedKey().isEmpty() ? Mono.empty()
                    : expiryPage(shard, now, limit, page.lastEvaluatedKey()), 1),
                4, 1)
            .concatMapIterable(QueryResponse::items, 1)
            .flatMap(row -> find(string(row, PersistenceConstants.PARTITION_KEY)
                .substring(PersistenceConstants.RESERVATION_PREFIX.length())), 8, 1)
            .filter(reservation -> reservation.status().isHeld() && reservation.expiredAt(now));
    }

    private Mono<QueryResponse> expiryPage(int shard, Instant now, int limit,
        Map<String, AttributeValue> cursor) {
        return Mono.fromFuture(() -> client.query(QueryRequest.builder().tableName(table)
            .indexName(PersistenceConstants.EXPIRY_INDEX)
            .keyConditionExpression(PersistenceConstants.DUE_EXPIRY_QUERY)
            .expressionAttributeValues(Map.of(PersistenceConstants.PARTITION_KEY_VALUE,
                s(PersistenceConstants.EXPIRY_PREFIX + shard), PersistenceConstants.TIME_VALUE, s(time(now))))
            .exclusiveStartKey(cursor).limit(limit).build()));
    }

    @Override
    public Mono<WorkLease> claimRecovery(String owner, Instant now) {
        return coordinate(PersistenceConstants.RECOVERY_LEASE, owner, now, 55, 60);
    }

    @Override
    public Mono<Void> releaseRecovery(String owner) {
        return release(PersistenceConstants.RECOVERY_LEASE, owner);
    }
}
