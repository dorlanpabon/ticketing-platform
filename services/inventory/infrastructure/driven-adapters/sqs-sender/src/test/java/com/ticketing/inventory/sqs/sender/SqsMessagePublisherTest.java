package com.ticketing.inventory.sqs.sender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.inventory.model.OutboxMessage;
import com.ticketing.inventory.sqs.sender.config.SqsPublisherConfig;
import com.ticketing.inventory.sqs.sender.config.SqsPublisherProperties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.test.StepVerifier;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class SqsMessagePublisherTest {
    @Test
    void routesEventsToTheirOwnerAndPropagatesPublicationFailure() {
        var sqs = mock(SqsAsyncClient.class);
        var sender = new SqsPublisherConfig().sqsSender(sqs,
            new SqsPublisherProperties("orders-url", "catalog-url", "expiry-url"), Clock.systemUTC());
        String body = "{\"occurredAt\":\"2026-11-16T12:00:00Z\"}";
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(
            CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("id").build()));
        StepVerifier.create(sender.publish(new OutboxMessage("1", "ORDERS", body, "lease"))).verifyComplete();
        StepVerifier.create(sender.publish(new OutboxMessage("2", "CATALOG", body, "lease")))
            .verifyComplete();
        var capture = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(2)).sendMessage(capture.capture());
        assertEquals("orders-url", capture.getAllValues().getFirst().queueUrl());
        assertEquals(body, capture.getAllValues().getLast().messageBody());
        assertThrows(IllegalArgumentException.class,
            () -> sender.publish(new OutboxMessage("3", "UNKNOWN", "{}", null)));
        when(sqs.sendMessage(any(SendMessageRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("SQS unavailable")));
        StepVerifier.create(sender.publish(new OutboxMessage("1", "ORDERS", body, null)))
            .expectError(IllegalStateException.class).verify();
    }

    @Test
    void timerDelayUsesOriginalDeadlineOnEachAttemptWithCeilingAndBounds() {
        var sqs = mock(SqsAsyncClient.class);
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-16T12:00:00Z");
        when(clock.instant()).thenReturn(now);
        var sender = new SqsMessagePublisher(sqs, "orders", "catalog", "expiry", clock);
        String body = "{\"type\":\"ExpireReservation\",\"occurredAt\":\"2026-11-16T12:00:00Z\",\"payload\":{\"orderId\":\"o1\",\"expiresAt\":\"2026-11-16T12:10:00Z\"}}";
        var message = new OutboxMessage("expiry:o1", "EXPIRATIONS", body, "lease");
        when(sqs.sendMessage(any(SendMessageRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));
        StepVerifier.create(sender.publish(message)).verifyComplete();
        when(clock.instant()).thenReturn(now.plusMillis(400));
        StepVerifier.create(sender.publish(message)).verifyComplete();
        when(clock.instant()).thenReturn(now.plusSeconds(120));
        StepVerifier.create(sender.publish(message)).verifyComplete();
        when(clock.instant()).thenReturn(now.plusSeconds(601));
        StepVerifier.create(sender.publish(message)).verifyComplete();
        when(clock.instant()).thenReturn(now.minusSeconds(10));
        StepVerifier.create(sender.publish(message)).verifyComplete();
        var capture = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(5)).sendMessage(capture.capture());
        assertEquals(List.of(600, 600, 480, 0, 600),
            capture.getAllValues().stream().map(SendMessageRequest::delaySeconds).toList());
        assertTrue(capture.getAllValues().stream()
            .allMatch(request -> request.queueUrl().equals("expiry") && request.messageBody().equals(body)));
    }
}
