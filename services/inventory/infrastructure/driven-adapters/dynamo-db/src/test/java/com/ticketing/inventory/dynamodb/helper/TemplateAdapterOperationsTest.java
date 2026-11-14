package com.ticketing.inventory.dynamodb.helper;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TemplateAdapterOperationsTest {
    @Test void retriesOnlyTransientServiceAndTransactionFailures() {
        var operations = new ExampleAdapter(mock(DynamoDbAsyncClient.class));
        for (String code : List.of("TransactionConflict", "ProvisionedThroughputExceeded", "ThrottlingError")) {
            assertTrue(operations.retryable(TransactionCanceledException.builder()
                    .cancellationReasons(CancellationReason.builder().code(code).build()).build()));
        }
        assertFalse(operations.retryable(TransactionCanceledException.builder()
                .cancellationReasons(CancellationReason.builder().code("ConditionalCheckFailed").build()).build()));
        assertTrue(operations.retryable(DynamoDbException.builder().statusCode(503).build()));
        assertTrue(operations.retryable(DynamoDbException.builder().statusCode(429).build()));
        assertFalse(operations.retryable(DynamoDbException.builder().statusCode(400).build()));
        assertFalse(operations.retryable(new IllegalArgumentException("Invalid request")));
    }

    @Test void transactionRetriesHaveAnExplicitBudgetAndStableToken() {
        var client = mock(DynamoDbAsyncClient.class);
        var operations = new ExampleAdapter(client);
        when(client.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(DynamoDbException.builder().statusCode(503).build()));
        StepVerifier.create(operations.write()).expectError(DynamoDbException.class).verify();
        var capture = org.mockito.ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(client, times(3)).transactWriteItems(capture.capture());
        assertEquals(1, capture.getAllValues().stream().map(TransactWriteItemsRequest::clientRequestToken).distinct().count());
    }

    private static final class ExampleAdapter extends TemplateAdapterOperations {
        ExampleAdapter(DynamoDbAsyncClient client) { super(client, "inventory"); }
        boolean retryable(Throwable error) { return transientFailure(error); }
        Mono<Void> write() { return transact(List.of()); }
    }
}
