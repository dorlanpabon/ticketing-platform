package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.model.*;
import com.ticketing.inventory.dynamodb.helper.TemplateAdapterOperations;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.json.JsonMapper;
import static com.ticketing.inventory.dynamodb.DynamoItems.*;

@Repository
public final class DynamoDBTemplateAdapter extends TemplateAdapterOperations implements InventoryStore {
    private static final String META = "META";
    private static final String ABSENT = "attribute_not_exists(pk)";
    private static final String EVENT_PREFIX = "EVENT#";
    private static final String STATE_ALIAS = "#state";
    private static final String STATE = "state";
    private static final String READY = "READY";
    private static final String NOT_READY = "INVENTORY_NOT_READY";
    private static final String COUNTERS = "COUNTERS";
    private static final String CAPACITY = "capacity";
    private static final String RECOVERY_LEASE = "LEASE#EXPIRY_SWEEP";

    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public DynamoDBTemplateAdapter(DynamoDbAsyncClient client, @Value("${inventory.table}") String table, Clock clock) {
        super(client, table);
        this.clock = clock;
    }

    @Override public Mono<Reservation> find(String orderId) {
        return get("RESERVATION#" + orderId, META)
                .map(ModelEntity::fromAttributes).map(entity -> json.readValue(entity.data(), Reservation.class));
    }

    @Override public Flux<Reservation> holders(String eventId, List<String> ticketIds) {
        if (ticketIds.isEmpty()) return Flux.empty();
        var reads = ticketIds.stream().distinct().map(id -> TransactGetItem.builder().get(Get.builder()
                .tableName(table).key(key(partition(eventId, shard(id)), ticketKey(id))).build()).build()).toList();
        return Mono.fromFuture(() -> client.transactGetItems(TransactGetItemsRequest.builder().transactItems(reads).build()))
                .flatMapMany(response -> Flux.fromIterable(response.responses())).map(ItemResponse::item)
                .filter(row -> row.containsKey("reservationId") && TicketStatus.valueOf(string(row, STATE)).isHeld())
                .map(row -> string(row, "reservationId")).distinct().flatMap(this::find, 8);
    }

    @Override public Mono<Reservation> reserve(ReservationRequest request) {
        return Mono.defer(() -> {
            var reservation = Reservation.accepted(request);
            var writes = stateRecords(reservation, ABSENT, Map.of(), Map.of());
            writes.add(TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(table)
                    .key(key(EVENT_PREFIX + request.eventId(), META))
                    .conditionExpression("#state = :ready")
                    .expressionAttributeNames(Map.of(STATE_ALIAS, STATE))
                    .expressionAttributeValues(Map.of(":ready", s(READY))).build()).build());
            for (String ticketId : request.ticketIds()) {
                writes.add(ticketTransition(request.eventId(), ticketId, TicketStatus.AVAILABLE,
                        reservation.status(), request.orderId(), request.customerId(), request.expiresAt()));
            }
            addCounters(writes, request.eventId(), request.ticketIds(), TicketStatus.AVAILABLE, reservation.status());
            if (reservation.status().isHeld()) {
                writes.add(put(expiryKey(reservation), ABSENT, Map.of(), Map.of()));
                writes.add(outbox("expiry:" + request.orderId(), "ExpireReservation", request.orderId(), 1,
                        Map.of("orderId", request.orderId(), "expiresAt", request.expiresAt().toString()), "EXPIRATIONS"));
            }
            return transact(writes, () -> requireBeforeDeadline(request.expiresAt(), "RESERVATION_EXPIRED"))
                    .thenReturn(reservation).onErrorResume(TransactionCanceledException.class, error -> {
                if (transientFailure(error)) return Mono.error(error);
                String code = error.cancellationReasons().size() > 3
                        && "ConditionalCheckFailed".equals(error.cancellationReasons().get(3).code())
                        ? NOT_READY : "TICKETS_UNAVAILABLE";
                return find(request.orderId()).flatMap(existing -> match(existing, request))
                        .switchIfEmpty(Mono.error(new InventoryException(code, NOT_READY.equals(code) ? 503 : 409)));
            });
        });
    }

    @Override public Mono<Reservation> reject(ReservationRequest request, String reason) {
        var result = Reservation.rejected(request, reason);
        return transact(stateRecords(result, ABSENT, Map.of(), Map.of()))
                .thenReturn(result).onErrorResume(TransactionCanceledException.class, error ->
                        find(request.orderId()).flatMap(existing -> match(existing, request))
                                .switchIfEmpty(Mono.error(error)));
    }

    private Mono<Reservation> match(Reservation reservation, ReservationRequest request) {
        return reservation.matches(request) ? Mono.just(reservation)
                : Mono.error(new InventoryException("IDEMPOTENCY_CONFLICT", 409));
    }

    @Override public Mono<Reservation> transition(Reservation expected, TicketStatus target, String resolution,
                                                  String commandId, Instant observedAt) {
        return Mono.defer(() -> {
            var next = expected.transition(target, resolution, commandId);
            String timeCondition = target == TicketStatus.AVAILABLE ? " <= " : " > ";
            var writes = stateRecords(next, "#version = :version AND #state = :state AND expiresAtMillis" + timeCondition + ":now",
                    Map.of("#version", "version", STATE_ALIAS, STATE),
                    Map.of(":version", n(expected.inventoryVersion()), ":state", s(expected.status().name()), ":now", n(observedAt.toEpochMilli())));
            for (String ticketId : expected.activeTicketIds()) {
                writes.add(ticketTransition(expected.eventId(), ticketId, expected.status(), target,
                        expected.orderId(), expected.customerId(), expected.expiresAt()));
            }
            addCounters(writes, expected.eventId(), expected.activeTicketIds(), expected.status(), target);
            if (!target.isHeld()) writes.add(TransactWriteItem.builder().delete(Delete.builder().tableName(table)
                    .key(expiryKey(expected)).build()).build());
            if (target == TicketStatus.SOLD) {
                var inbox = item("INBOX#" + commandId, META);
                inbox.put("ttl", n(clock.instant().plus(Duration.ofDays(30)).getEpochSecond()));
                writes.add(put(inbox, ABSENT, Map.of(), Map.of()));
            }
            return transact(writes, () -> {
                if (target != TicketStatus.AVAILABLE) requireBeforeDeadline(expected.expiresAt(), "CONCURRENT_TRANSITION");
            }).thenReturn(next).onErrorMap(TransactionCanceledException.class,
                    error -> transientFailure(error) ? error : new InventoryException("CONCURRENT_TRANSITION", 409));
        });
    }

    private void requireBeforeDeadline(Instant deadline, String code) {
        if (!clock.instant().isBefore(deadline)) throw new InventoryException(code, 409);
    }

    private Map<String, AttributeValue> expiryKey(Reservation reservation) {
        return key("EVENT_EXPIRY#" + reservation.eventId() + "#" + Math.floorMod(reservation.orderId().hashCode(), 16),
                time(reservation.expiresAt()) + "#" + reservation.orderId());
    }

    private List<TransactWriteItem> stateRecords(Reservation reservation, String condition,
            Map<String, String> names, Map<String, AttributeValue> values) {
        var writes = new ArrayList<TransactWriteItem>();
        var row = new ModelEntity("RESERVATION#" + reservation.orderId(), META, json.writeValueAsString(reservation)).attributes();
        row.put("version", n(reservation.inventoryVersion()));
        row.put(STATE, s(reservation.status().name()));
        row.put("expiresAtMillis", n(reservation.expiresAt().toEpochMilli()));
        if (reservation.status().isHeld()) {
            row.put("gsi2pk", s("EXPIRY#" + Math.floorMod(reservation.orderId().hashCode(), 16)));
            row.put("gsi2sk", s(time(reservation.expiresAt())));
        }
        writes.add(put(row, condition, names, values));
        var audit = item("AUDIT#" + reservation.orderId(), String.format(java.util.Locale.ROOT, "%020d", reservation.inventoryVersion()));
        audit.put("data", s(json.writeValueAsString(reservation)));
        audit.put("occurredAt", s(clock.instant().toString()));
        writes.add(put(audit, ABSENT, Map.of(), Map.of()));
        writes.add(outbox("reservation:" + reservation.orderId() + ":" + reservation.inventoryVersion(),
                "ReservationStateChanged", reservation.orderId(), reservation.inventoryVersion(), reservation, "ORDERS"));
        return writes;
    }

    private TransactWriteItem ticketTransition(String eventId, String ticketId, TicketStatus source,
            TicketStatus target, String reservationId, String customerId, Instant expiresAt) {
        var values = new HashMap<String, AttributeValue>();
        values.put(":source", s(source.name()));
        values.put(":target", s(target.name()));
        values.put(":reservation", s(reservationId));
        String condition = "#state = :source";
        String update;
        if (target == TicketStatus.AVAILABLE) {
            condition += " AND reservationId = :reservation";
            update = "SET #state = :target REMOVE reservationId, customerId, expiresAtMillis";
        } else {
            if (source != TicketStatus.AVAILABLE) condition += " AND reservationId = :reservation";
            values.put(":customer", s(customerId));
            values.put(":expires", n(expiresAt.toEpochMilli()));
            update = "SET #state = :target, reservationId = :reservation, customerId = :customer, expiresAtMillis = :expires";
        }
        return TransactWriteItem.builder().update(Update.builder().tableName(table)
                .key(key(partition(eventId, shard(ticketId)), ticketKey(ticketId)))
                .conditionExpression(condition).updateExpression(update)
                .expressionAttributeNames(Map.of(STATE_ALIAS, STATE)).expressionAttributeValues(values).build()).build();
    }

    private void addCounters(List<TransactWriteItem> writes, String eventId, List<String> tickets,
            TicketStatus source, TicketStatus target) {
        var counts = tickets.stream().collect(Collectors.groupingBy(DynamoItems::shard, Collectors.counting()));
        counts.forEach((shard, count) -> writes.add(TransactWriteItem.builder().update(Update.builder().tableName(table)
                .key(key(partition(eventId, shard), COUNTERS))
                .conditionExpression("#source >= :count")
                .updateExpression("SET #source = #source - :count, #target = #target + :count")
                .expressionAttributeNames(Map.of("#source", source.name(), "#target", target.name()))
                .expressionAttributeValues(Map.of(":count", n(count))).build()).build()));
    }

    private TransactWriteItem put(Map<String, AttributeValue> row, String condition,
                                  Map<String, String> names, Map<String, AttributeValue> values) {
        var builder = Put.builder().tableName(table).item(row).conditionExpression(condition);
        if (!names.isEmpty()) builder.expressionAttributeNames(names);
        if (!values.isEmpty()) builder.expressionAttributeValues(values);
        return TransactWriteItem.builder().put(builder.build()).build();
    }

    private TransactWriteItem outbox(String id, String type, String aggregateId, long version, Object payload, String destination) {
        var now = clock.instant();
        var message = Map.of("messageId", id, "type", type, "schemaVersion", 1, "aggregateId", aggregateId,
                "aggregateVersion", version, "correlationId", aggregateId, "causationId", aggregateId,
                "occurredAt", now.toString(), "payload", payload);
        var row = item("OUTBOX#" + id, META);
        row.put("body", s(json.writeValueAsString(message)));
        row.put("destination", s(destination));
        row.put("gsi1pk", s("OUTBOX#" + Math.floorMod(id.hashCode(), 16)));
        row.put("gsi1sk", s(time(now)));
        row.put("leaseUntil", n(0));
        return put(row, ABSENT, Map.of(), Map.of());
    }

    @Override public Mono<Void> provision(String eventId, int capacity) {
        return initialize(eventId, capacity)
                .thenMany(Flux.range(0, (capacity + 47) / 48))
                .concatMap(chunk -> provisionChunk(eventId, capacity, chunk))
                .then(completeProvision(eventId, capacity));
    }

    private Mono<Void> initialize(String eventId, int capacity) {
        return get(EVENT_PREFIX + eventId, META).flatMap(existing -> {
            if (number(existing, CAPACITY) != capacity) return Mono.error(new InventoryException("EVENT_CAPACITY_CONFLICT", 409));
            return Mono.just(true);
        }).switchIfEmpty(Mono.defer(() -> {
            var writes = new ArrayList<TransactWriteItem>();
            var event = item(EVENT_PREFIX + eventId, META);
            event.put(STATE, s("PROVISIONING")); event.put(CAPACITY, n(capacity)); event.put("initialized", n(0));
            writes.add(put(event, ABSENT, Map.of(), Map.of()));
            for (int shard = 0; shard < 16; shard++) {
                var counter = item(partition(eventId, shard), COUNTERS);
                for (TicketStatus state : TicketStatus.values()) counter.put(state.name(), n(0));
                writes.add(put(counter, ABSENT, Map.of(), Map.of()));
            }
            return transact(writes).thenReturn(true).onErrorResume(TransactionCanceledException.class,
                    error -> initialize(eventId, capacity).thenReturn(true));
        })).then();
    }

    private Mono<Void> provisionChunk(String eventId, int capacity, int chunk) {
        return get(EVENT_PREFIX + eventId, "CHUNK#" + chunk).map(ignored -> true)
                .switchIfEmpty(Mono.defer(() -> {
                    var writes = new ArrayList<TransactWriteItem>();
                    var counts = new HashMap<Integer, Integer>();
                    int first = chunk * 48 + 1, end = Math.min(capacity, first + 47);
                    for (int ticket = first; ticket <= end; ticket++) {
                        String id = Integer.toString(ticket);
                        var row = item(partition(eventId, shard(id)), ticketKey(id));
                        row.put("eventId", s(eventId)); row.put("ticketId", s(id)); row.put(STATE, s("AVAILABLE"));
                        writes.add(put(row, ABSENT, Map.of(), Map.of()));
                        counts.merge(shard(id), 1, Integer::sum);
                    }
                    counts.forEach((shard, count) -> writes.add(TransactWriteItem.builder().update(Update.builder()
                            .tableName(table).key(key(partition(eventId, shard), COUNTERS))
                            .updateExpression("SET #available = #available + :count")
                            .expressionAttributeNames(Map.of("#available", "AVAILABLE"))
                            .expressionAttributeValues(Map.of(":count", n(count))).build()).build()));
                    writes.add(put(item(EVENT_PREFIX + eventId, "CHUNK#" + chunk), ABSENT, Map.of(), Map.of()));
                    writes.add(TransactWriteItem.builder().update(Update.builder().tableName(table)
                            .key(key(EVENT_PREFIX + eventId, META)).conditionExpression("#state = :provisioning")
                            .updateExpression("SET initialized = initialized + :count")
                            .expressionAttributeNames(Map.of(STATE_ALIAS, STATE))
                            .expressionAttributeValues(Map.of(":provisioning", s("PROVISIONING"), ":count", n(end - first + 1))).build()).build());
                    return transact(writes).thenReturn(true).onErrorResume(TransactionCanceledException.class,
                            error -> get(EVENT_PREFIX + eventId, "CHUNK#" + chunk).map(ignored -> true).switchIfEmpty(Mono.error(error)));
                })).then();
    }

    private Mono<Void> completeProvision(String eventId, int capacity) {
        var writes = new ArrayList<TransactWriteItem>();
        writes.add(TransactWriteItem.builder().update(Update.builder().tableName(table).key(key(EVENT_PREFIX + eventId, META))
                .conditionExpression("#state = :provisioning AND initialized = :capacity")
                .updateExpression("SET #state = :ready").expressionAttributeNames(Map.of(STATE_ALIAS, STATE))
                .expressionAttributeValues(Map.of(":provisioning", s("PROVISIONING"), ":ready", s(READY), ":capacity", n(capacity))).build()).build());
        writes.add(outbox("inventory-ready:" + eventId, "InventoryReady", eventId, 1, Map.of("eventId", eventId, CAPACITY, capacity), "CATALOG"));
        return transact(writes).onErrorResume(TransactionCanceledException.class, error -> get(EVENT_PREFIX + eventId, META)
                .filter(row -> READY.equals(string(row, STATE))).switchIfEmpty(Mono.error(error)).then());
    }

    @Override public Mono<Availability> availability(String eventId) {
        var requests = new ArrayList<TransactGetItem>();
        requests.add(TransactGetItem.builder().get(Get.builder().tableName(table).key(key(EVENT_PREFIX + eventId, META)).build()).build());
        for (int shard = 0; shard < 16; shard++) requests.add(TransactGetItem.builder().get(Get.builder()
                .tableName(table).key(key(partition(eventId, shard), COUNTERS)).build()).build());
        return Mono.fromFuture(() -> client.transactGetItems(TransactGetItemsRequest.builder().transactItems(requests).build()))
                .map(response -> {
                    var metadata = response.responses().getFirst().item();
                    if (metadata.isEmpty()) throw new InventoryException("EVENT_NOT_FOUND", 404);
                    if (!READY.equals(string(metadata, STATE))) throw new InventoryException(NOT_READY, 409);
                    long[] totals = new long[5];
                    for (int index = 1; index < response.responses().size(); index++) {
                        var row = response.responses().get(index).item();
                        for (TicketStatus state : TicketStatus.values()) totals[state.ordinal()] += number(row, state.name());
                    }
                    return new Availability(eventId, number(metadata, CAPACITY), totals[0], totals[1], totals[2], totals[3], totals[4]);
                }).flatMap(snapshot -> assertFresh(eventId).thenReturn(snapshot));
    }

    private Mono<Void> assertFresh(String eventId) {
        return Flux.range(0, 16).flatMap(shard -> Mono.fromFuture(() -> client.query(QueryRequest.builder()
                .tableName(table).consistentRead(true).keyConditionExpression("pk = :pk AND sk < :deadline")
                .expressionAttributeValues(Map.of(":pk", s("EVENT_EXPIRY#" + eventId + "#" + shard),
                        ":deadline", s(time(clock.instant().minusSeconds(5)))))
                .limit(1).build())), 4).any(response -> !response.items().isEmpty())
                .flatMap(stale -> stale ? Mono.error(new InventoryException("INVENTORY_STALE", 503)) : Mono.empty());
    }

    @Override public Mono<TicketPage> tickets(String eventId, int limit, String cursor) {
        int start;
        try { start = cursor == null ? 1 : Integer.parseInt(cursor); }
        catch (NumberFormatException error) { return Mono.error(new InventoryException("INVALID_CURSOR", 400)); }
        if (start < 1 || start > 100_000) return Mono.error(new InventoryException("INVALID_CURSOR", 400));
        return get(EVENT_PREFIX + eventId, META).switchIfEmpty(Mono.error(new InventoryException("EVENT_NOT_FOUND", 404)))
                .flatMap(metadata -> {
                    if (!READY.equals(string(metadata, STATE))) return Mono.error(new InventoryException(NOT_READY, 409));
                    int capacity = (int) number(metadata, CAPACITY), last = Math.min(capacity, start + limit - 1);
                    if (start > capacity) return Mono.just(new TicketPage(List.of(), null));
                    var reads = new ArrayList<TransactGetItem>();
                    for (int ticket = start; ticket <= last; ticket++) {
                        String id = Integer.toString(ticket);
                        reads.add(TransactGetItem.builder().get(Get.builder().tableName(table).key(key(partition(eventId, shard(id)), ticketKey(id))).build()).build());
                    }
                    return Mono.fromFuture(() -> client.transactGetItems(TransactGetItemsRequest.builder().transactItems(reads).build()))
                            .map(response -> new TicketPage(response.responses().stream().map(ItemResponse::item)
                                    .map(row -> new Ticket(eventId, string(row, "ticketId"), TicketStatus.valueOf(string(row, STATE)))).toList(),
                                    last < capacity ? Integer.toString(last + 1) : null));
                });
    }

    @Override public Flux<Reservation> expired(Instant now, int limit) {
        return Flux.range(0, 16).flatMap(shard -> expiryPage(shard, now, limit, Map.of())
                        .expand(page -> page.lastEvaluatedKey().isEmpty() ? Mono.empty()
                                : expiryPage(shard, now, limit, page.lastEvaluatedKey()), 1), 4, 1)
                .concatMapIterable(QueryResponse::items, 1)
                .flatMap(row -> find(string(row, "pk").substring("RESERVATION#".length())), 8, 1)
                .filter(reservation -> reservation.status().isHeld() && reservation.expiredAt(now));
    }

    private Mono<QueryResponse> expiryPage(int shard, Instant now, int limit, Map<String, AttributeValue> cursor) {
        return Mono.fromFuture(() -> client.query(QueryRequest.builder().tableName(table).indexName("gsi2")
                .keyConditionExpression("gsi2pk = :pk AND gsi2sk <= :time")
                .expressionAttributeValues(Map.of(":pk", s("EXPIRY#" + shard), ":time", s(time(now))))
                .exclusiveStartKey(cursor).limit(limit).build()));
    }

    @Override public Mono<WorkLease> claimRecovery(String owner, Instant now) {
        return coordinate(RECOVERY_LEASE, owner, now, 55, 60);
    }

    @Override public Mono<Void> releaseRecovery(String owner) {
        return release(RECOVERY_LEASE, owner);
    }
}
