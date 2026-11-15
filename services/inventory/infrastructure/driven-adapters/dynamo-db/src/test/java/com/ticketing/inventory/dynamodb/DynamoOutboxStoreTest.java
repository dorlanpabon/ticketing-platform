package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.model.OutboxMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static com.ticketing.inventory.dynamodb.DynamoItems.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamoOutboxStoreTest {
    @Test void queriesDueMessagesAndLeasesThenMarksOnlyItsOwnLease() {
        var client = mock(DynamoDbAsyncClient.class);
        var store = new DynamoOutboxStore(client, "inventory");
        var now = Instant.parse("2026-11-16T12:00:00Z");
        when(client.query(any(QueryRequest.class))).thenAnswer(call -> {
            QueryRequest request = call.getArgument(0);
            var items = "OUTBOX#0".equals(string(request.expressionAttributeValues(), ":pk"))
                    ? List.of(Map.of("pk", s("OUTBOX#event"), "destination", s("ORDERS"), "body", s("{}"))) : List.<Map<String, AttributeValue>>of();
            return CompletableFuture.completedFuture(QueryResponse.builder().items(items).build());
        });
        StepVerifier.create(store.pending(now, 10)).expectNext(new OutboxMessage("OUTBOX#event", "ORDERS", "{}", null)).verifyComplete();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));
        var message = new OutboxMessage("OUTBOX#event", "ORDERS", "{}", null);
        StepVerifier.create(store.claim(message, now).flatMap(claimed -> {
            assertNotNull(claimed.leaseId());
            return store.sent(claimed, now);
        })).verifyComplete();
        var capture = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(client, times(2)).updateItem(capture.capture());
        assertTrue(capture.getAllValues().getFirst().conditionExpression().contains("leaseUntil <= :now"));
        assertEquals("leaseId = :lease", capture.getAllValues().getLast().conditionExpression());
        assertTrue(capture.getAllValues().getLast().updateExpression().contains("REMOVE gsi1pk, gsi1sk"));
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        StepVerifier.create(store.claim(message, now)).verifyComplete();
    }

    @Test void coordinatorCachesOnlyItsOwnedLeaseAndFollowerUsesPersistedDeadline() {
        var client = mock(DynamoDbAsyncClient.class);
        var store = new DynamoOutboxStore(client, "inventory");
        var now = Instant.parse("2026-11-16T12:00:00Z");
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));
        StepVerifier.create(store.coordinate("owner", now)).expectNext(new com.ticketing.inventory.model.WorkLease(true, now.plusSeconds(30))).verifyComplete();
        StepVerifier.create(store.coordinate("owner", now.plusSeconds(9))).expectNext(new com.ticketing.inventory.model.WorkLease(true, now.plusSeconds(30))).verifyComplete();
        verify(client, times(1)).updateItem(any(UpdateItemRequest.class));
        StepVerifier.create(store.coordinate("owner", now.plusSeconds(10))).expectNext(new com.ticketing.inventory.model.WorkLease(true, now.plusSeconds(40))).verifyComplete();
        when(client.updateItem(any(UpdateItemRequest.class))).thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().build()));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder()
                .item(Map.of("leaseUntil", n(now.plusSeconds(40).toEpochMilli()), "nextRunAt", n(0))).build()));
        StepVerifier.create(store.coordinate("follower", now.plusSeconds(11))).expectNext(new com.ticketing.inventory.model.WorkLease(false, now.plusSeconds(40))).verifyComplete();
        verify(client, never()).query(any(QueryRequest.class));
        when(client.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()));
        StepVerifier.create(store.coordinate("follower", now)).expectNext(new com.ticketing.inventory.model.WorkLease(false, now.plusMillis(500))).verifyComplete();
    }
}
