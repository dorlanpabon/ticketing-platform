package com.ticketing.orders.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.OrderSubmission;
import com.ticketing.orders.model.OutboxEntry;
import com.ticketing.orders.model.Reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivecommons.utils.ObjectMapperImp;

import reactor.test.StepVerifier;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class OrderRepositoryTest {
    private final Instant now = Instant.parse("2026-11-16T12:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Map<String, AttributeValue>> rows = new HashMap<>();
    private DynamoDbAsyncClient client;
    private OrderRepository adapter;

    @BeforeEach
    void setup() {
        client = mock(DynamoDbAsyncClient.class);
        adapter = new OrderRepository(client, "orders", Clock.fixed(now, ZoneOffset.UTC),
            new ObjectMapperImp());
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest read = call.getArgument(0);
            return CompletableFuture.completedFuture(GetItemResponse.builder()
                .item(rows.getOrDefault(read.key().get("pk").s(), Map.of())).build());
        });
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));
        when(client.deleteItem(any(DeleteItemRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(DeleteItemResponse.builder().build()));
    }

    private Order order() {
        return new Order("o1", "e1", "c1", List.of("1"), List.of(), "AVAILABLE", "RESERVING", "", 0, now,
            now.plusSeconds(600), "PURCHASE", "c1", "");
    }

    private Reservation state(String status, long version) {
        return new Reservation("o1", "e1", "c1", status, "NONE", version, List.of("1"), List.of("1"),
            now.plusSeconds(600));
    }

    private Envelope message(String id) {
        return new Envelope(id, "PurchaseRequested", 1, "o1", "o1", "o1", now, Map.of("orderId", "o1"));
    }

    private Map<String, AttributeValue> data(Object value) {
        return Map.of("data", AttributeValue.fromS(json.writeValueAsString(value)));
    }

    private Map<String, AttributeValue> idempotency(String fingerprint, String id) {
        return Map.of("fingerprint", AttributeValue.fromS(fingerprint), "entityId", AttributeValue.fromS(id));
    }

    private Map<String, AttributeValue> purchaseKey(String fingerprint, String id, String messageId) {
        var row = new HashMap<>(idempotency(fingerprint, id));
        row.put("purchaseMessageId", AttributeValue.fromS(messageId));
        return row;
    }

    private TransactionCanceledException conflict() {
        return TransactionCanceledException.builder()
            .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build()).build();
    }

    @Test
    void creationIsAtomicAndReplaysPersistedEntity() {
        var submitted = new OrderSubmission(order(), "m1");
        StepVerifier.create(adapter.create(order(), "key", "hash", message("m1"))).expectNext(submitted)
            .verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(capture.capture());
        assertEquals(3, capture.getValue().transactItems().size());
        assertTrue(capture.getValue().transactItems().getFirst().put().item().containsKey("gsi1pk"));
        var purchaseKey = capture.getValue().transactItems().get(1).put().item();
        assertEquals("m1", purchaseKey.get("purchaseMessageId").s());
        rows.put("IDEMP#key", purchaseKey);
        rows.put("ORDER#o1", data(order()));
        StepVerifier.create(adapter.create(order(), "key", "hash", message("ignored"))).expectNext(submitted)
            .verifyComplete();
        StepVerifier.create(adapter.create(order(), "key", "different", message("ignored")))
            .expectError(DomainFailure.class).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void concurrentCreateRecoversWinnerAndUnknownFailuresPropagate() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(call -> {
            rows.put("IDEMP#key", purchaseKey("hash", "o1", "winner"));
            rows.put("ORDER#o1", data(order()));
            return CompletableFuture.failedFuture(conflict());
        });
        StepVerifier.create(adapter.create(order(), "key", "hash", message("m1")))
            .expectNext(new OrderSubmission(order(), "winner")).verifyComplete();
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(conflict()));
        rows.clear();
        StepVerifier.create(adapter.create(order(), "key", "hash", message("m1")))
            .expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void legacyLinkRaceUsesThePersistedMessageAndUnresolvedFailuresPropagate() {
        rows.put("ORDER#o1", data(order()));
        rows.put("IDEMP#legacy", idempotency("hash", "o1"));
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(call -> {
            rows.put("IDEMP#legacy", purchaseKey("hash", "o1", "winner"));
            return CompletableFuture.failedFuture(conflict());
        });
        StepVerifier.create(adapter.create(order(), "legacy", "hash", message("new")))
            .expectNext(new OrderSubmission(order(), "winner")).verifyComplete();
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(conflict()));
        rows.put("IDEMP#legacy", idempotency("hash", "o1"));
        StepVerifier.create(adapter.create(order(), "legacy", "hash", message("new")))
            .expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void confirmationUsesOneDurableCommandAndRecordsEveryHttpKey() {
        StepVerifier.create(adapter.confirm(order(), "first", message("confirm-o1"))).verifyComplete();
        rows.put("IDEMP#first", idempotency("o1", "o1"));
        StepVerifier.create(adapter.confirm(order(), "first", message("confirm-o1"))).verifyComplete();
        rows.put("COMMAND#confirm-o1", data(Map.of("orderId", "o1")));
        StepVerifier.create(adapter.confirm(order(), "second", message("confirm-o1"))).verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, times(2)).transactWriteItems(capture.capture());
        assertEquals(3, capture.getAllValues().getFirst().transactItems().size());
        assertEquals(1, capture.getAllValues().getLast().transactItems().size());
    }

    @Test
    void losingConcurrentConfirmationStillPersistsItsOwnKey() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(call -> {
            TransactWriteItemsRequest transaction = call.getArgument(0);
            if (transaction.transactItems().size() > 1) {
                rows.put("COMMAND#confirm-o1", data(Map.of("orderId", "o1")));
                return CompletableFuture.failedFuture(conflict());
            }
            rows.put("IDEMP#second", idempotency("o1", "o1"));
            return CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build());
        });
        StepVerifier.create(adapter.confirm(order(), "second", message("confirm-o1"))).verifyComplete();
        assertTrue(rows.containsKey("IDEMP#second"));
        verify(client, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void projectsOnlyIncreasingVersionsAndKeepsFinalOrdersOutOfReconciliation() {
        rows.put("ORDER#o1", data(order()));
        StepVerifier.create(adapter.project(state("SOLD", 3), "sold-event")).verifyComplete();
        var capture = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(capture.capture());
        var projected = capture.getValue().transactItems().getFirst().put();
        assertEquals("#version = :old", projected.conditionExpression());
        assertFalse(projected.item().containsKey("gsi1pk"));
        rows.put("ORDER#o1", data(order().withInventory(state("SOLD", 3))));
        StepVerifier.create(adapter.project(state("RESERVED", 1), "old-event")).verifyComplete();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
        rows.clear();
        StepVerifier.create(adapter.project(state("SOLD", 3), "unknown")).expectError(DomainFailure.class)
            .verify();
    }

    @Test
    void projectionRaceAcceptsOnlyAnAlreadyNewerState() {
        rows.put("ORDER#o1", data(order()));
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenAnswer(call -> {
            rows.put("ORDER#o1", data(order().withInventory(state("SOLD", 3))));
            return CompletableFuture.failedFuture(conflict());
        });
        StepVerifier.create(adapter.project(state("RESERVED", 1), "old-event")).verifyComplete();
        rows.put("ORDER#o1", data(order()));
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(conflict()));
        StepVerifier.create(adapter.project(state("SOLD", 3), "new-event"))
            .expectError(TransactionCanceledException.class).verify();
    }

    @Test
    void reconciliationUsesDueIndexAndConditionalReschedule() {
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> {
            QueryRequest query = call.getArgument(0);
            var entries = "RECONCILE#0".equals(query.expressionAttributeValues().get(":partition").s())
                ? List.of(data(order()))
                : List.<Map<String, AttributeValue>>of();
            return CompletableFuture.completedFuture(QueryResponse.builder().items(entries).build());
        });
        StepVerifier.create(adapter.due()).expectNext(order()).verifyComplete();
        var sequence = inOrder(client);
        sequence.verify(client).updateItem(argThat(
            (UpdateItemRequest request) -> request.key().get("pk").s().equals("COORDINATOR#RECONCILIATION")));
        sequence.verify(client, times(16)).query(any(QueryRequest.class));
        StepVerifier.create(adapter.due()).verifyComplete();
        verify(client, times(16)).query(any(QueryRequest.class));
        StepVerifier.create(adapter.reviewed(order())).verifyComplete();
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        StepVerifier.create(adapter.reviewed(order())).verifyComplete();
        verify(client, never()).scan(any(ScanRequest.class));
    }

    @Test
    void reconciliationFollowerCannotQueryBeforeTakingTheDurableLease() {
        var heldLease = ConditionalCheckFailedException.builder()
            .item(Map.of("leaseUntil", AttributeValue.fromN(Long.toString(now.toEpochMilli() + 30000))))
            .build();
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(heldLease));
        StepVerifier.create(adapter.due()).verifyComplete();
        StepVerifier.create(adapter.due()).verifyComplete();
        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
        verify(client, never()).query(any(QueryRequest.class));
    }

    @Test
    void stableHoldsAreReviewedEveryMinuteWhilePendingIntentsUseFiveSeconds() {
        StepVerifier.create(adapter.reviewed(order())).verifyComplete();
        StepVerifier.create(adapter.reviewed(order().withInventory(state("RESERVED", 1)))).verifyComplete();
        var updates = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client, times(2)).updateItem(updates.capture());
        assertEquals(OrderTable.time(now.toEpochMilli() + 5000),
            updates.getAllValues().getFirst().expressionAttributeValues().get(":due").s());
        assertEquals(OrderTable.time(now.toEpochMilli() + 60000),
            updates.getAllValues().getLast().expressionAttributeValues().get(":due").s());
        assertTrue(updates.getValue().updateExpression().contains("REMOVE reviewFailures"));
    }

    @Test
    void failedReviewsPersistBoundedBackoffWithoutChangingTheBusinessRecord() {
        long[] failures = { 0, 1, 2, 3, 4, 20 };
        long[] delays = { 5000, 10000, 20000, 40000, 60000, 60000 };
        for (int index = 0; index < failures.length; index++) {
            Map<String, AttributeValue> row = new HashMap<>(data(order()));
            if (failures[index] > 0)
                row.put("reviewFailures", AttributeValue.fromN(Long.toString(failures[index])));
            rows.put("ORDER#o1", row);
            StepVerifier.create(adapter.reviewFailed(order())).verifyComplete();
        }
        var updates = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client, times(failures.length)).updateItem(updates.capture());
        for (int index = 0; index < delays.length; index++) {
            UpdateItemRequest request = updates.getAllValues().get(index);
            assertEquals(OrderTable.time(now.toEpochMilli() + delays[index]),
                request.expressionAttributeValues().get(":due").s());
            assertEquals("SET gsi1sk = :due ADD reviewFailures :one", request.updateExpression());
            assertEquals("0", request.expressionAttributeValues().get(":version").n());
            assertTrue(request.conditionExpression().contains("attribute_exists(gsi1pk)"));
        }
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        StepVerifier.create(adapter.reviewFailed(order())).verifyComplete();
        rows.clear();
        clearInvocations(client);
        StepVerifier.create(adapter.reviewFailed(order())).verifyComplete();
        verify(client, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void outboxClaimsAreConditionalAndAcknowledgementsRequireLeaseOwnership() {
        var row = new HashMap<>(data(message("message")));
        row.put("pk", AttributeValue.fromS("OUTBOX#message"));
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> {
            QueryRequest query = call.getArgument(0);
            return CompletableFuture
                .completedFuture(QueryResponse.builder()
                    .items("OUTBOX#0".equals(query.expressionAttributeValues().get(":partition").s())
                        ? List.of(row)
                        : List.of())
                    .build());
        });
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(
            CompletableFuture.completedFuture(UpdateItemResponse.builder().attributes(row).build()));
        StepVerifier.create(adapter.claim()).assertNext(entry -> {
            assertEquals("OUTBOX#message", entry.key());
            assertNotNull(entry.leaseOwner());
            assertEquals(message("message"), entry.envelope());
        }).verifyComplete();
        StepVerifier
            .create(adapter.acknowledge(new OutboxEntry("OUTBOX#message", "lease", message("message"))))
            .verifyComplete();
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        StepVerifier.create(adapter.claim()).verifyComplete();
        when(client.deleteItem(any(DeleteItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        StepVerifier.create(adapter.acknowledge(new OutboxEntry("OUTBOX#message", "old", message("message"))))
            .verifyComplete();
    }

    @Test
    void directOutboxOperationsUseThePrimaryKeyAndOwnerCondition() {
        var envelope = message("message");
        var entry = new OutboxEntry("OUTBOX#message", "owner", envelope);
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture
            .completedFuture(UpdateItemResponse.builder().attributes(data(envelope)).build()));
        StepVerifier.create(adapter.claim(envelope.messageId()))
            .assertNext(claimed -> assertEquals(envelope, claimed.envelope())).verifyComplete();
        StepVerifier.create(adapter.release(entry)).verifyComplete();
        var updates = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client, times(2)).updateItem(updates.capture());
        for (var update : updates.getAllValues()) {
            assertEquals(entry.key(), update.key().get("pk").s());
        }
        assertEquals("leaseOwner = :owner", updates.getValue().conditionExpression());
        assertEquals("owner", updates.getValue().expressionAttributeValues().get(":owner").s());
        assertEquals("0", updates.getValue().expressionAttributeValues().get(":until").n());
        rows.put(entry.key(), data(envelope));
        StepVerifier.create(adapter.pending(envelope.messageId())).expectNext(true).verifyComplete();
        verify(client).getItem(argThat((GetItemRequest request) -> request.consistentRead()
            && entry.key().equals(request.key().get("pk").s())));
        verify(client, never()).query(any(QueryRequest.class));
        when(client.updateItem(any(UpdateItemRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        StepVerifier.create(adapter.release(entry)).verifyComplete();
    }

    @Test
    void modelMetadataDeclaresBothBaseAndSecondaryKeys() {
        var entity = new TableEntity();
        entity.setPartitionKey("ORDER#o1");
        entity.setPayloadJson("{}");
        entity.setSortKey("META");
        entity.setIndexPartition("RECONCILE#0");
        entity.setIndexSort("0000000000000001");
        assertEquals("ORDER#o1", entity.getPartitionKey());
        assertEquals("{}", entity.getPayloadJson());
        assertEquals("META", entity.getSortKey());
        assertEquals("RECONCILE#0", entity.getIndexPartition());
        assertEquals("0000000000000001", entity.getIndexSort());
        var schema = TableSchema.fromBean(TableEntity.class);
        assertEquals("gsi1pk", schema.tableMetadata().indexPartitionKey("gsi1"));
        assertEquals("gsi1sk", schema.tableMetadata().indexSortKey("gsi1").orElseThrow());
    }
}
