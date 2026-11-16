package com.ticketing.orders.sqs.sender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.OutboxEntry;
import com.ticketing.orders.model.gateways.OutboxPort;
import com.ticketing.orders.model.utils.ErrorCodes;
import com.ticketing.orders.sqs.sender.config.SqsEnqueueProperties;
import com.ticketing.orders.sqs.sender.config.SqsPublisherProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class SqsOrderEnqueuerTest {
    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final Duration RETRY_DELAY = Duration.ofMillis(50);
    private static final String APPLICATION_ID = "purchase-message";

    private final OutboxPort port = mock(OutboxPort.class);
    private final SqsAsyncClient sqs = mock(SqsAsyncClient.class);
    private final SqsCommandPublisher sender = new SqsCommandPublisher(sqs,
        new SqsPublisherProperties("us-east-1", "queue", null));
    private final SqsOrderEnqueuer enqueuer = new SqsOrderEnqueuer(port, new OutboxDispatcher(port, sender),
        new SqsEnqueueProperties(TIMEOUT, RETRY_DELAY));
    private final OutboxEntry entry = new OutboxEntry("OUTBOX#purchase-message", "owner",
        new Envelope(APPLICATION_ID, "PurchaseRequested", 1, "order", "order", "order", Instant.EPOCH,
            Map.of("orderId", "order")));

    @Test
    void waitsForSqsReceiptAndOutboxAcknowledgement() {
        var reception = new CompletableFuture<SendMessageResponse>();
        Sinks.Empty<Void> acknowledgement = Sinks.empty();
        when(port.claim(APPLICATION_ID)).thenReturn(Mono.just(entry));
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(reception);
        when(port.acknowledge(entry)).thenReturn(acknowledgement.asMono());

        StepVerifier.withVirtualTime(() -> enqueuer.enqueue(APPLICATION_ID)).expectSubscription()
            .then(() -> verify(sqs).sendMessage(any(SendMessageRequest.class)))
            .then(() -> verify(port, never()).acknowledge(any())).expectNoEvent(Duration.ofMillis(1))
            .then(() -> reception.complete(SendMessageResponse.builder().messageId("transport-id").build()))
            .then(() -> verify(port).acknowledge(entry)).expectNoEvent(Duration.ofMillis(1))
            .then(() -> acknowledgement.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST)).verifyComplete();
        verify(port, never()).release(any());
    }

    @Test
    void waitsForAnotherPublisherAndReusesItsAcknowledgement() {
        when(port.claim(APPLICATION_ID)).thenReturn(Mono.empty());
        when(port.pending(APPLICATION_ID)).thenReturn(Mono.just(true), Mono.just(false));

        StepVerifier.withVirtualTime(() -> enqueuer.enqueue(APPLICATION_ID)).expectSubscription()
            .expectNoEvent(RETRY_DELAY.minusMillis(1)).thenAwait(Duration.ofMillis(1)).verifyComplete();
        verify(port, times(2)).pending(APPLICATION_ID);
        verifyNoInteractions(sqs);
    }

    @Test
    void timesOutWhenAnotherPublisherNeverAcknowledges() {
        when(port.claim(APPLICATION_ID)).thenReturn(Mono.empty());
        when(port.pending(APPLICATION_ID)).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> enqueuer.enqueue(APPLICATION_ID)).expectSubscription()
            .thenAwait(TIMEOUT).expectErrorSatisfies(SqsOrderEnqueuerTest::assertUnavailable).verify();
        verifyNoInteractions(sqs);
        verify(port, never()).release(any());
    }

    @Test
    void releasesItsLeaseWhenSqsDoesNotConfirmBeforeTimeout() {
        when(port.claim(APPLICATION_ID)).thenReturn(Mono.just(entry));
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(new CompletableFuture<>());
        when(port.release(entry)).thenReturn(Mono.empty());

        StepVerifier.withVirtualTime(() -> enqueuer.enqueue(APPLICATION_ID)).expectSubscription()
            .thenAwait(TIMEOUT).expectErrorSatisfies(SqsOrderEnqueuerTest::assertUnavailable).verify();
        verify(port).release(entry);
        verify(port, never()).acknowledge(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = " ")
    void rejectsAMissingSqsMessageIdAndLeavesTheOutboxPending(String messageId) {
        when(port.claim(APPLICATION_ID)).thenReturn(Mono.just(entry));
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(
            CompletableFuture.completedFuture(SendMessageResponse.builder().messageId(messageId).build()));
        when(port.release(entry)).thenReturn(Mono.empty());

        StepVerifier.create(enqueuer.enqueue(APPLICATION_ID))
            .expectErrorSatisfies(SqsOrderEnqueuerTest::assertUnavailable).verify();
        verify(port).release(entry);
        verify(port, never()).acknowledge(any());
    }

    private static void assertUnavailable(Throwable error) {
        var failure = assertInstanceOf(DomainFailure.class, error);
        assertEquals(503, failure.status());
        assertEquals(ErrorCodes.ORDER_ENQUEUE_UNAVAILABLE, failure.code());
    }
}
