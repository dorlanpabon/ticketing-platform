package com.ticketing.inventory.sqs.listener.helper;

import com.ticketing.inventory.sqs.listener.SQSProcessor;
import com.ticketing.inventory.usecase.InventoryUseCase;
import com.ticketing.inventory.usecase.OutboxUseCase;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SQSListenerTest {
    @Test void acknowledgesAfterBusinessCompletionExtendsVisibilityAndExpiresDuringOutage() {
        var time = VirtualTimeScheduler.create();
        VirtualTimeScheduler.set(time);
        var sqs = mock(SqsAsyncClient.class);
        var processor = mock(SQSProcessor.class);
        var inventory = mock(InventoryUseCase.class);
        var outbox = mock(OutboxUseCase.class);
        var message = Message.builder().messageId("transport").receiptHandle("receipt").body("{}").build();
        var result = Sinks.<Void>empty();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
                CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(message).build()), new CompletableFuture<>());
        when(processor.apply(message)).thenReturn(result.asMono());
        when(sqs.deleteMessage(any(DeleteMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
        when(sqs.changeMessageVisibility(any(ChangeMessageVisibilityRequest.class))).thenReturn(CompletableFuture.completedFuture(ChangeMessageVisibilityResponse.builder().build()));
        when(outbox.dispatch(100)).thenReturn(Mono.error(new IllegalStateException("Queue unavailable")));
        when(inventory.recoverExpired(eq(100), any())).thenAnswer(call -> {
            ((java.util.function.LongConsumer) call.getArgument(1)).accept(3);
            return Mono.just(1L);
        });
        var listener = new SQSListener(sqs, processor, inventory, outbox, "queue", "expiry", "local", true, java.time.Clock.systemUTC());
        try {
            listener.stop();
            assertFalse(listener.isRunning());
            listener.start(); listener.start();
            assertTrue(listener.isRunning());
            time.advanceTimeBy(Duration.ofSeconds(21));
            verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
            verify(sqs).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
            verify(inventory, times(1)).recoverExpired(eq(100), any());
            time.advanceTimeBy(Duration.ofSeconds(40));
            verify(inventory, times(2)).recoverExpired(eq(100), any());
            result.tryEmitEmpty();
            verify(sqs).deleteMessage(argThat((DeleteMessageRequest request) -> "receipt".equals(request.receiptHandle())));
            listener.stop();
            assertFalse(listener.isRunning());
        } finally { listener.stop(); VirtualTimeScheduler.reset(); }
    }

    @Test void separateExpiryConsumerDefersEarlyTimersWithoutAcknowledgingOrPollingRecoveryInAws() {
        var time = VirtualTimeScheduler.create();
        VirtualTimeScheduler.set(time);
        var sqs = mock(SqsAsyncClient.class);
        var processor = mock(SQSProcessor.class);
        var inventory = mock(InventoryUseCase.class);
        var outbox = mock(OutboxUseCase.class);
        var now = java.time.Instant.parse("2026-11-16T12:00:00Z");
        var message = Message.builder().messageId("early").receiptHandle("timer-receipt")
                .body("{\"payload\":{\"expiresAt\":\"2026-11-16T12:00:01.400Z\"}}").build();
        var delivered = new java.util.concurrent.atomic.AtomicBoolean();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(call -> {
            ReceiveMessageRequest request = call.getArgument(0);
            return request.queueUrl().equals("expiry") && delivered.compareAndSet(false, true)
                    ? CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(message).build()) : new CompletableFuture<>();
        });
        when(processor.apply(message)).thenReturn(Mono.error(new com.ticketing.inventory.model.InventoryException("RESERVATION_NOT_DUE", 409)));
        when(sqs.changeMessageVisibility(any(ChangeMessageVisibilityRequest.class))).thenReturn(CompletableFuture.completedFuture(ChangeMessageVisibilityResponse.builder().build()));
        when(outbox.dispatch(100)).thenReturn(Mono.just(new com.ticketing.inventory.model.DispatchBatch(0, 30000)));
        var listener = new SQSListener(sqs, processor, inventory, outbox, "queue", "expiry", "aws", false, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        try {
            listener.start();
            verify(sqs).changeMessageVisibility(argThat((ChangeMessageVisibilityRequest request) -> request.queueUrl().equals("expiry")
                    && request.receiptHandle().equals("timer-receipt") && request.visibilityTimeout() == 2));
            verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
            time.advanceTimeBy(Duration.ofSeconds(61));
            verifyNoInteractions(inventory);
        } finally { listener.stop(); VirtualTimeScheduler.reset(); }
    }

    @Test void retainsFailedMessagesAndRecoversReceiveErrorsWithoutStoppingMaintenance() {
        var time = VirtualTimeScheduler.create();
        VirtualTimeScheduler.set(time);
        var sqs = mock(SqsAsyncClient.class);
        var processor = mock(SQSProcessor.class);
        var inventory = mock(InventoryUseCase.class);
        var outbox = mock(OutboxUseCase.class);
        var message = Message.builder().messageId("transport").receiptHandle("receipt").build();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("Unavailable")),
                CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(message).build()), new CompletableFuture<>());
        when(processor.apply(message)).thenReturn(Mono.error(new IllegalArgumentException("Malformed")));
        when(outbox.dispatch(100)).thenReturn(Mono.just(new com.ticketing.inventory.model.DispatchBatch(0, 0)));
        when(inventory.recoverExpired(eq(100), any())).thenReturn(Mono.error(new IllegalStateException("Dynamo unavailable")));
        var listener = new SQSListener(sqs, processor, inventory, outbox, "queue", "expiry", "local", true, java.time.Clock.systemUTC());
        try {
            listener.start();
            time.advanceTimeBy(Duration.ofSeconds(2));
            verify(sqs, atLeast(2)).receiveMessage(any(ReceiveMessageRequest.class));
            verify(processor).apply(message);
            verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
            assertTrue(listener.isRunning());
        } finally { listener.stop(); VirtualTimeScheduler.reset(); }
    }
}
