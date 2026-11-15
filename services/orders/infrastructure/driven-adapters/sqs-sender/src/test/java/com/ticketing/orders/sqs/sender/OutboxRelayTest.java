package com.ticketing.orders.sqs.sender;

import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.OutboxEntry;
import com.ticketing.orders.model.gateways.OutboxPort;
import com.ticketing.orders.sqs.sender.config.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.metrics.MetricPublisher;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    @Test void sendsBeforeAcknowledgingAndRetainsFailedPublication() {
        var port = mock(OutboxPort.class);
        var sqs = mock(SqsAsyncClient.class);
        var sender = new SQSSender(sqs, new SQSSenderProperties("us-east-1", "queue", null));
        var relay = new OutboxRelay(port, sender);
        var envelope = Envelope.create("PurchaseRequested", "order", Map.of("orderId", "order"), Instant.EPOCH);
        var entry = new OutboxEntry("OUTBOX#id", "lease", envelope);
        when(port.claim()).thenReturn(Flux.just(entry));
        when(port.acknowledge(entry)).thenReturn(Mono.empty());
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("transport").build()));
        StepVerifier.create(relay.publish()).verifyComplete();
        var ordering = inOrder(sqs, port);
        ordering.verify(sqs).sendMessage(any(SendMessageRequest.class)); ordering.verify(port).acknowledge(entry);
        clearInvocations(port, sqs);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        StepVerifier.create(relay.publish()).expectError(IllegalStateException.class).verify();
        verify(port, never()).acknowledge(any());
    }

    @Test void optionalEndpointSupportsAwsAndLocalConfigurations() {
        var config = new SQSSenderConfig();
        var metric = mock(MetricPublisher.class);
        try (var local = config.configSqs(new SQSSenderProperties("us-east-1", "queue", "http://localhost:19324"), metric);
             var aws = config.configSqs(new SQSSenderProperties("us-east-1", "queue", null), metric);
             var blank = config.configSqs(new SQSSenderProperties("us-east-1", "queue", ""), metric)) {
            assertTrue(local.serviceClientConfiguration().endpointOverride().isPresent());
            assertTrue(aws.serviceClientConfiguration().endpointOverride().isEmpty());
            assertTrue(blank.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }
}
