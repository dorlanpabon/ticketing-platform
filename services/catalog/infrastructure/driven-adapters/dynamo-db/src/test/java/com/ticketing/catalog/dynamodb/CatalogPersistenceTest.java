package com.ticketing.catalog.dynamodb;

import com.ticketing.catalog.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivecommons.utils.ObjectMapper;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogPersistenceTest {
    private final DynamoDbAsyncClient client = mock(DynamoDbAsyncClient.class);
    private final Instant now = Instant.parse("2026-11-16T00:00:00Z");
    private final DynamoDBTemplateAdapter adapter = new DynamoDBTemplateAdapter(client, "catalog", Clock.fixed(now, ZoneOffset.UTC), mock(ObjectMapper.class));
    private final Event event = new Event("e1", "Concert", "Arena", now.plusSeconds(60), 10, "PROVISIONING");
    private final Envelope message = Envelope.create("ProvisionInventory", "e1", Map.of("eventId", "e1", "capacity", 10), now);
    private final JsonMapper json = JsonMapper.builder().build();
    private Map<String, AttributeValue> row(Object value) { return Map.of("data", AttributeValue.fromS(json.writeValueAsString(value))); }
    private GetItemResponse found(Object value) { return GetItemResponse.builder().item(row(value)).build(); }
    private GetItemResponse previous(String fingerprint) { return GetItemResponse.builder().item(Map.of("fingerprint", AttributeValue.fromS(fingerprint), "entityId", AttributeValue.fromS("e1"))).build(); }
    private void reads(GetItemResponse... values) {
        var answers = new ArrayDeque<>(List.of(values));
        when(client.getItem(any(GetItemRequest.class))).thenAnswer(call -> CompletableFuture.completedFuture(answers.remove()));
    }
    private void transaction() { when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build())); }

    @Test void createCommitsEventIdempotencyAndOutboxTogether() {
        reads(GetItemResponse.builder().build()); transaction();
        StepVerifier.create(adapter.create(event, "key", "fingerprint", message)).expectNext(event).verifyComplete();
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client).transactWriteItems(request.capture());
        var writes = request.getValue().transactItems(); assertEquals(3, writes.size());
        assertEquals("EVENT#e1", writes.getFirst().put().item().get("pk").s());
        assertEquals("attribute_not_exists(pk)", writes.getFirst().put().conditionExpression());
        assertTrue(writes.get(1).put().item().containsKey("ttl"));
        assertTrue(writes.get(2).put().item().get("gsi1pk").s().startsWith("OUTBOX#"));
        assertEquals("0", writes.get(2).put().item().get("leaseUntil").n());
    }

    @Test void idempotentReplayAndConflictDoNotWrite() {
        reads(previous("fingerprint"), found(event));
        StepVerifier.create(adapter.create(event, "key", "fingerprint", message)).expectNext(event).verifyComplete();
        reads(previous("different"));
        StepVerifier.create(adapter.create(event, "key", "fingerprint", message)).expectErrorSatisfies(error -> assertEquals("IDEMPOTENCY_CONFLICT", ((DomainFailure)error).code())).verify();
        verify(client, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test void concurrentCreateReturnsWinnerOrPreservesFailure() {
        var conflict = TransactionCanceledException.builder().message("race").build();
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.failedFuture(conflict));
        reads(GetItemResponse.builder().build(), previous("fingerprint"), found(event));
        StepVerifier.create(adapter.create(event, "key", "fingerprint", message)).expectNext(event).verifyComplete();
        reads(GetItemResponse.builder().build(), GetItemResponse.builder().build());
        StepVerifier.create(adapter.create(event, "key", "fingerprint", message)).expectError(TransactionCanceledException.class).verify();
    }

    @Test void listUsesIndexAndRoundTripsOpaqueCursor() {
        var last = Map.of("pk", AttributeValue.fromS("EVENT#e1"), "gsi2sk", AttributeValue.fromS("date#e1"));
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().items(row(event)).lastEvaluatedKey(last).build()));
        EventPage page = adapter.list(10, null).block(); assertNotNull(page); assertEquals(List.of(event), page.items()); assertNotNull(page.cursor());
        when(client.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(QueryResponse.builder().build()));
        StepVerifier.create(adapter.list(10, page.cursor())).assertNext(next -> assertNull(next.cursor())).verifyComplete();
        StepVerifier.create(adapter.list(10, " ")).expectNextCount(1).verifyComplete();
        ArgumentCaptor<QueryRequest> requests = ArgumentCaptor.forClass(QueryRequest.class); verify(client, times(3)).query(requests.capture());
        assertEquals("gsi2", requests.getValue().indexName());
        assertEquals("EVENT#e1", requests.getAllValues().get(1).exclusiveStartKey().get("pk").s());
        for (String malformed : List.of("%%", encoded("wrong"), encoded("x".repeat(161)+"\nkey"), encoded("key\n"+"x".repeat(201))))
            StepVerifier.create(adapter.list(10, malformed)).expectError(DomainFailure.class).verify();
    }
    private String encoded(String value) { return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }

    @Test void activationWritesInboxAndIndexAndIgnoresCompletedReplay() {
        reads(found(event)); transaction();
        StepVerifier.create(adapter.activate("e1", "m1")).verifyComplete();
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class); verify(client).transactWriteItems(request.capture());
        assertEquals("ACTIVE_EVENTS", request.getValue().transactItems().getFirst().put().item().get("gsi2pk").s());
        assertEquals("INBOX#m1", request.getValue().transactItems().get(1).put().item().get("pk").s());
        reads(found(event.activate())); StepVerifier.create(adapter.activate("e1", "m1")).verifyComplete();
        reads(GetItemResponse.builder().build()); StepVerifier.create(adapter.activate("missing", "m1")).expectError(DomainFailure.class).verify();
        verify(client, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test void activationRaceAcceptsOnlyAlreadyActiveWinner() {
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class))).thenReturn(CompletableFuture.failedFuture(TransactionCanceledException.builder().message("race").build()));
        reads(found(event), found(event.activate())); StepVerifier.create(adapter.activate("e1", "m1")).verifyComplete();
        reads(found(event), found(event)); StepVerifier.create(adapter.activate("e1", "m1")).expectError(TransactionCanceledException.class).verify();
    }

    @Test void outboxUsesConditionalLeasesAndOwnershipAcknowledgement() {
        Map<String, AttributeValue> stored = new HashMap<>(row(message)); stored.put("pk", AttributeValue.fromS("OUTBOX#m1"));
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> CompletableFuture.completedFuture(QueryResponse.builder().items(
                ((QueryRequest)call.getArgument(0)).expressionAttributeValues().get(":partition").s().equals("OUTBOX#0") ? List.of(stored) : List.of()).build()));
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().attributes(stored).build()));
        OutboxEntry entry = adapter.claim().single().block(); assertNotNull(entry); assertEquals(message, entry.envelope());
        ArgumentCaptor<UpdateItemRequest> claim = ArgumentCaptor.forClass(UpdateItemRequest.class); verify(client, times(2)).updateItem(claim.capture());
        assertEquals("attribute_exists(pk) AND leaseUntil < :now", claim.getValue().conditionExpression());
        when(client.deleteItem(any(DeleteItemRequest.class))).thenReturn(CompletableFuture.completedFuture(DeleteItemResponse.builder().build()));
        StepVerifier.create(adapter.acknowledge(entry)).verifyComplete();
        ArgumentCaptor<DeleteItemRequest> deletion = ArgumentCaptor.forClass(DeleteItemRequest.class); verify(client).deleteItem(deletion.capture());
        assertEquals(entry.leaseOwner(), deletion.getValue().expressionAttributeValues().get(":owner").s());
        var conflict = ConditionalCheckFailedException.builder().message("other owner").build();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.failedFuture(conflict));
        StepVerifier.create(adapter.claim()).verifyComplete();
        when(client.deleteItem(any(DeleteItemRequest.class))).thenReturn(CompletableFuture.failedFuture(conflict));
        StepVerifier.create(adapter.acknowledge(entry)).verifyComplete();
    }
}
