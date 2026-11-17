package com.ticketing.inventory.dynamodb;

import static com.ticketing.inventory.dynamodb.ItemAttributes.key;
import static com.ticketing.inventory.dynamodb.ItemAttributes.n;
import static com.ticketing.inventory.dynamodb.ItemAttributes.number;
import static com.ticketing.inventory.dynamodb.ItemAttributes.s;
import static com.ticketing.inventory.dynamodb.ItemAttributes.string;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.inventory.model.Availability;
import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.Reservation;
import com.ticketing.inventory.model.ReservationIntent;
import com.ticketing.inventory.model.TicketPage;
import com.ticketing.inventory.model.TicketStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.test.StepVerifier;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class InventoryRepositoryTest {
    private final Instant now = Instant.parse("2026-11-16T12:00:00Z");
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private DynamoDbAsyncClient client;
    private InventoryRepository store;
    private ReservationIntent request;
    private final Map<String, Map<String, AttributeValue>> rows = new HashMap<>();

    @BeforeEach
    void setup() {
        client = mock(DynamoDbAsyncClient.class);
        store = new InventoryRepository(client, "inventory", Clock.fixed(now, ZoneOffset.UTC));
        request = new ReservationIntent("o1", "e1", "c1", List.of("1", "17"), now, now.plusSeconds(600), null,
            null, null);
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest read = call.getArgument(0);
            var row = rows.getOrDefault(string(read.key(), "pk") + "/" + string(read.key(), "sk"), Map.of());
            var result = new HashMap<String, AttributeValue>();
            if (!row.isEmpty()) {
                result.putAll(read.key());
                result.putAll(row);
            }
            return CompletableFuture.completedFuture(GetItemResponse.builder().item(result).build());
        });
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        when(client.query(any(QueryRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(List.of()).build()));
    }

    @Test
    void reservationIncludesEverySeatOneGroupedCounterAndDurableRecords() {
        StepVerifier.create(store.reserve(request)).expectNext(Reservation.accepted(request))
            .verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(capture.capture());
        var writes = capture.getValue().transactItems();
        assertEquals(9, writes.size());
        assertNotNull(capture.getValue().clientRequestToken());
        assertEquals(1,
            writes.stream()
                .filter(row -> row.update() != null && "COUNTERS".equals(string(row.update().key(), "sk")))
                .count());
        assertTrue(writes.stream().anyMatch(row -> row.conditionCheck() != null));
        assertTrue(writes.stream().filter(row -> row.update() != null)
            .allMatch(row -> row.update().conditionExpression() != null));
        var hold = writes.getFirst().put().item();
        assertTrue(hold.containsKey("gsi2pk"));
        assertEquals(request.expiresAt().toEpochMilli(), number(hold, "expiresAtMillis"));
        var timer = writes.stream()
            .filter(
                write -> write.put() != null && "OUTBOX#expiry:o1".equals(string(write.put().item(), "pk")))
            .findFirst().orElseThrow().put().item();
        assertEquals("EXPIRATIONS", string(timer, "destination"));
        assertEquals(request.expiresAt().toString(),
            json.readTree(string(timer, "body")).path("payload").path("expiresAt").asText());
    }

    @Test
    void confirmationAndReleaseAreCompareAndSwapTransactions() {
        var reserved = Reservation.accepted(request);
        var pending = reserved.transition(TicketStatus.PENDING_CONFIRMATION, "NONE", "cmd");
        StepVerifier.create(store.transition(reserved, TicketStatus.PENDING_CONFIRMATION, "NONE", "cmd", now))
            .expectNext(pending).verifyComplete();
        StepVerifier.create(store.transition(pending, TicketStatus.SOLD, "NONE", "cmd", now))
            .expectNextMatches(value -> value.status() == TicketStatus.SOLD).verifyComplete();
        StepVerifier
            .create(store.transition(reserved, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null,
                now.plusSeconds(600)))
            .expectNextMatches(value -> value.activeTicketIds().isEmpty()).verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, times(3)).transactWriteItems(capture.capture());
        assertTrue(capture.getAllValues().get(0).transactItems().getFirst().put().conditionExpression()
            .contains("expiresAtMillis > :now"));
        assertTrue(capture.getAllValues().get(1).transactItems().stream()
            .anyMatch(row -> row.put() != null && string(row.put().item(), "pk").startsWith("INBOX#")));
        assertTrue(capture.getAllValues().get(2).transactItems().getFirst().put().conditionExpression()
            .contains("expiresAtMillis <= :now"));
        assertFalse(
            capture.getAllValues().get(2).transactItems().getFirst().put().item().containsKey("gsi2pk"));
    }

    @Test
    void conditionalFailureUsesExistingResultOrReturnsConflict() {
        failTransaction();
        StepVerifier.create(store.reserve(request)).expectErrorMatches(
            error -> error instanceof InventoryException value && "TICKETS_UNAVAILABLE".equals(value.code()))
            .verify();
        var reserved = Reservation.accepted(request);
        rows.put("RESERVATION#o1/META", Map.of("data", s(json.writeValueAsString(reserved))));
        StepVerifier.create(store.reserve(request)).expectNext(reserved).verifyComplete();
        var changed = new ReservationIntent("o1", "e2", "c1", request.ticketIds(), now, request.expiresAt(),
            null, null, null);
        StepVerifier.create(store.reserve(changed)).expectErrorMatches(
            error -> error instanceof InventoryException value && "IDEMPOTENCY_CONFLICT".equals(value.code()))
            .verify();
        StepVerifier
            .create(store.transition(reserved, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now))
            .expectErrorMatches(error -> error instanceof InventoryException value
                && "CONCURRENT_TRANSITION".equals(value.code()))
            .verify();
    }

    @Test
    void rejectionIsDurableAndCannotOverwriteSuccessfulReservation() {
        var rejected = Reservation.rejected(request, "TICKETS_UNAVAILABLE");
        StepVerifier.create(store.reject(request, "TICKETS_UNAVAILABLE")).expectNext(rejected)
            .verifyComplete();
        failTransaction();
        var reserved = Reservation.accepted(request);
        rows.put("RESERVATION#o1/META", Map.of("data", s(json.writeValueAsString(reserved))));
        StepVerifier.create(store.reject(request, "TICKETS_UNAVAILABLE")).expectNext(reserved)
            .verifyComplete();
        rows.clear();
        StepVerifier.create(store.reject(request, "TICKETS_UNAVAILABLE"))
            .expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void provisionsChunksBeforePublishingReadyAndRejectsCapacityChanges() {
        StepVerifier.create(store.provision("e1", 100)).verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, times(5)).transactWriteItems(capture.capture());
        assertEquals(17, capture.getAllValues().getFirst().transactItems().size());
        assertTrue(capture.getAllValues().stream().allMatch(tx -> tx.transactItems().size() <= 100));
        assertEquals(2, capture.getAllValues().getLast().transactItems().size());
        rows.put("EVENT#e1/META", Map.of("capacity", n(50), "state", s("READY")));
        StepVerifier.create(store.provision("e1", 100))
            .expectErrorMatches(error -> error instanceof InventoryException value
                && "EVENT_CAPACITY_CONFLICT".equals(value.code()))
            .verify();
    }

    @Test
    void availabilityUsesOneTransactionalSnapshotAndRejectsUnreadyEvents() {
        when(client.transactGetItems(any(TransactGetItemsRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransactGetItemsResponse.builder()
                .responses(ItemResponse.builder().item(Map.of()).build()).build()));
        StepVerifier.create(store.availability("missing")).expectError(InventoryException.class).verify();
        when(client.transactGetItems(any(TransactGetItemsRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransactGetItemsResponse.builder()
                .responses(ItemResponse.builder().item(Map.of("state", s("PROVISIONING"))).build()).build()));
        StepVerifier.create(store.availability("e1")).expectError(InventoryException.class).verify();
        var result = new ArrayList<ItemResponse>();
        result.add(ItemResponse.builder().item(Map.of("state", s("READY"), "capacity", n(16))).build());
        for (int i = 0; i < 16; i++)
            result.add(ItemResponse.builder().item(Map.of("AVAILABLE", n(1), "RESERVED", n(0),
                "PENDING_CONFIRMATION", n(0), "SOLD", n(0), "COMPLIMENTARY", n(0))).build());
        when(client.transactGetItems(any(TransactGetItemsRequest.class))).thenReturn(
            CompletableFuture.completedFuture(TransactGetItemsResponse.builder().responses(result).build()));
        StepVerifier.create(store.availability("e1")).expectNext(new Availability("e1", 16, 16, 0, 0, 0, 0))
            .verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactGetItemsRequest.class);
        verify(client, times(3)).transactGetItems(capture.capture());
        assertEquals(17, capture.getValue().transactItems().size());
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture
            .completedFuture(QueryResponse.builder().items(key("EVENT_EXPIRY#e1#0", "expired")).build()));
        StepVerifier.create(store.availability("e1"))
            .expectErrorMatches(error -> error instanceof InventoryException failure
                && "INVENTORY_STALE".equals(failure.code()) && failure.status() == 503)
            .verify();
        var queries = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, atLeastOnce()).query(queries.capture());
        assertTrue(queries.getAllValues().stream()
            .allMatch(query -> query.consistentRead() && query.indexName() == null && query.limit() == 1));
    }

    @Test
    void selectedTicketHoldersAreStronglyReadAndDeduplicated() {
        StepVerifier.create(store.holders("e1", List.of())).verifyComplete();
        rows.put("RESERVATION#o1/META",
            Map.of("data", s(json.writeValueAsString(Reservation.accepted(request)))));
        when(client.transactGetItems(any(TransactGetItemsRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransactGetItemsResponse.builder().responses(
                ItemResponse.builder().item(Map.of()).build(),
                ItemResponse.builder().item(Map.of("reservationId", s("o1"), "state", s("RESERVED"))).build(),
                ItemResponse.builder().item(Map.of("reservationId", s("o1"), "state", s("RESERVED"))).build(),
                ItemResponse.builder().item(Map.of("reservationId", s("sold"), "state", s("SOLD"))).build())
                .build()));
        StepVerifier.create(store.holders("e1", List.of("1", "1", "2", "3", "4")))
            .expectNext(Reservation.accepted(request)).verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactGetItemsRequest.class);
        verify(client).transactGetItems(capture.capture());
        assertEquals(4, capture.getValue().transactItems().size());
        verify(client, times(1)).getItem(any(GetItemRequest.class));
    }

    @Test
    void paginatesNumberedTicketsWithoutScanning() {
        StepVerifier.create(store.tickets("missing", 2, null)).expectError(InventoryException.class).verify();
        for (String cursor : List.of("bad", "0", "100001"))
            StepVerifier.create(store.tickets("e1", 2, cursor)).expectError(InventoryException.class)
                .verify();
        rows.put("EVENT#e1/META", Map.of("state", s("PROVISIONING"), "capacity", n(3)));
        StepVerifier.create(store.tickets("e1", 2, null)).expectError(InventoryException.class).verify();
        rows.put("EVENT#e1/META", Map.of("state", s("READY"), "capacity", n(3)));
        when(client.transactGetItems(any(TransactGetItemsRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransactGetItemsResponse.builder()
                .responses(
                    ItemResponse.builder().item(Map.of("ticketId", s("1"), "state", s("AVAILABLE"))).build(),
                    ItemResponse.builder().item(Map.of("ticketId", s("2"), "state", s("SOLD"))).build())
                .build()));
        StepVerifier.create(store.tickets("e1", 2, null))
            .expectNextMatches(page -> page.items().size() == 2 && "3".equals(page.nextCursor()))
            .verifyComplete();
        StepVerifier.create(store.tickets("e1", 2, "3")).expectNextMatches(page -> page.nextCursor() == null)
            .verifyComplete();
        StepVerifier.create(store.tickets("e1", 2, "4")).expectNext(new TicketPage(List.of(), null))
            .verifyComplete();
        verify(client, never()).scan(any(ScanRequest.class));
    }

    @Test
    void expiryQueriesIndexAndRechecksAuthoritativeState() {
        var oldRequest = new ReservationIntent("o1", "e1", "c1", List.of("1"), now.minusSeconds(600), now,
            null, null, null);
        var old = Reservation.accepted(oldRequest);
        rows.put("RESERVATION#o1/META", Map.of("data", s(json.writeValueAsString(old))));
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> {
            QueryRequest query = call.getArgument(0);
            boolean first = "EXPIRY#0".equals(string(query.expressionAttributeValues(), ":pk"));
            return CompletableFuture.completedFuture(QueryResponse.builder()
                .items(first ? List.of(key("RESERVATION#o1", "META")) : List.of()).build());
        });
        StepVerifier.create(store.expired(now, 10)).expectNext(old).verifyComplete();
        rows.put("RESERVATION#o1/META", Map.of("data",
            s(json.writeValueAsString(old.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null)))));
        StepVerifier.create(store.expired(now, 10)).verifyComplete();
    }

    private void failTransaction() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build())
                .build()));
    }

    @Test
    void recoveryFollowsEveryIndexPageEvenWhenFirstPageContainsStaleEntries() {
        var expired = Reservation.accepted(new ReservationIntent("due", "e1", "c1", List.of("1"),
            now.minusSeconds(600), now, null, null, null));
        rows.put("RESERVATION#due/META", Map.of("data", s(json.writeValueAsString(expired))));
        var cursor = key("RESERVATION#gone", "META");
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> {
            QueryRequest query = call.getArgument(0);
            if (!"EXPIRY#0".equals(string(query.expressionAttributeValues(), ":pk")))
                return CompletableFuture.completedFuture(QueryResponse.builder().items(List.of()).build());
            return CompletableFuture.completedFuture(query.exclusiveStartKey().isEmpty()
                ? QueryResponse.builder().items(cursor).lastEvaluatedKey(cursor).build()
                : QueryResponse.builder().items(key("RESERVATION#due", "META")).build());
        });
        StepVerifier.create(store.expired(now, 1)).expectNext(expired).verifyComplete();
        verify(client, times(17)).query(any(QueryRequest.class));
    }

    @Test
    void retryRechecksDeadlineBeforeAnyLateWrite() {
        var moving = mock(Clock.class);
        when(moving.instant()).thenReturn(now);
        var bounded = new InventoryRepository(client, "inventory", moving);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(call -> {
            when(moving.instant()).thenReturn(request.expiresAt());
            return CompletableFuture.failedFuture(DynamoDbException.builder().statusCode(503).build());
        });
        StepVerifier.create(bounded.reserve(request))
            .expectErrorMatches(error -> error instanceof InventoryException failure
                && "RESERVATION_EXPIRED".equals(failure.code()))
            .verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
        var original = Reservation.accepted(request);
        StepVerifier
            .create(bounded.transition(original, TicketStatus.PENDING_CONFIRMATION, "NONE", "cmd", now))
            .expectErrorMatches(error -> error instanceof InventoryException failure
                && "CONCURRENT_TRANSITION".equals(failure.code()))
            .verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }
}
