package com.ticketing.orders.sqs.sender;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.OutboxEntry;
import com.ticketing.orders.model.gateways.OutboxPort;
import com.ticketing.orders.sqs.sender.config.SqsPublisherConfig;
import com.ticketing.orders.sqs.sender.config.SqsPublisherProperties;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class OutboxRelayTest {
    @Test
    void sendsBeforeAcknowledgingAndRetainsFailedPublication() {
        var port = mock(OutboxPort.class);
        var sqs = mock(SqsAsyncClient.class);
        var sender = new SqsCommandPublisher(sqs, new SqsPublisherProperties("us-east-1", "queue", null));
        var relay = new OutboxRelay(port, new OutboxDispatcher(port, sender));
        var envelope = Envelope.create("PurchaseRequested", "order", Map.of("orderId", "order"),
            Instant.EPOCH);
        var entry = new OutboxEntry("OUTBOX#id", "lease", envelope);
        when(port.claim()).thenReturn(Flux.just(entry));
        when(port.acknowledge(entry)).thenReturn(Mono.empty());
        when(port.release(entry)).thenReturn(Mono.empty());
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(
            CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("transport").build()));
        StepVerifier.create(relay.publish()).verifyComplete();
        var ordering = inOrder(sqs, port);
        ordering.verify(sqs).sendMessage(any(SendMessageRequest.class));
        ordering.verify(port).acknowledge(entry);
        clearInvocations(port, sqs);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        StepVerifier.create(relay.publish()).expectError(IllegalStateException.class).verify();
        verify(port, never()).acknowledge(any());
        verify(port).release(entry);
    }

    @Test
    void optionalEndpointSupportsAwsAndLocalConfigurations() {
        var config = new SqsPublisherConfig();
        var metric = mock(MetricPublisher.class);
        try (
            var local = config.configSqs(
                new SqsPublisherProperties("us-east-1", "queue", "http://localhost:19324"), metric);
            var aws = config.configSqs(new SqsPublisherProperties("us-east-1", "queue", null), metric);
            var blank = config.configSqs(new SqsPublisherProperties("us-east-1", "queue", ""), metric)) {
            assertTrue(local.serviceClientConfiguration().endpointOverride().isPresent());
            assertTrue(aws.serviceClientConfiguration().endpointOverride().isEmpty());
            assertTrue(blank.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }
}
