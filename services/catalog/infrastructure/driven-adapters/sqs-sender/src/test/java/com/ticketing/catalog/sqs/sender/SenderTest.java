package com.ticketing.catalog.sqs.sender;

import com.ticketing.catalog.model.*;
import com.ticketing.catalog.model.gateways.OutboxPort;
import com.ticketing.catalog.sqs.sender.config.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.metrics.MetricPublisher;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SenderTest {
    @Test void sendsEnvelopeThenAcknowledgesOnlySuccessfulDelivery() {
        SqsAsyncClient client = mock(SqsAsyncClient.class);
        OutboxPort port = mock(OutboxPort.class);
        SQSSender sender = new SQSSender(client, new SQSSenderProperties("us-east-1", "http://localhost/queue", null));
        Envelope envelope = Envelope.create("ProvisionInventory", "e", Map.of("eventId", "e"), Instant.EPOCH);
        OutboxEntry entry = new OutboxEntry("key", "owner", envelope);
        when(port.claim()).thenReturn(Flux.just(entry)); when(port.acknowledge(entry)).thenReturn(Mono.empty());
        when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("sent").build()));
        StepVerifier.create(new OutboxRelay(port, sender).publish()).verifyComplete();
        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class); verify(client).sendMessage(request.capture());
        assertEquals("http://localhost/queue", request.getValue().queueUrl());
        assertEquals(envelope, JsonMapper.builder().build().readValue(request.getValue().messageBody(), Envelope.class));
        verify(port).acknowledge(entry);
    }

    @Test void failedDeliveryNeverSubscribesToAcknowledgement() {
        OutboxPort port = mock(OutboxPort.class); SQSSender sender = mock(SQSSender.class);
        OutboxEntry entry = new OutboxEntry("key", "owner", Envelope.create("ProvisionInventory", "e", Map.of(), Instant.EPOCH));
        java.util.concurrent.atomic.AtomicBoolean acknowledged = new java.util.concurrent.atomic.AtomicBoolean();
        when(port.claim()).thenReturn(Flux.just(entry)); when(sender.send(anyString())).thenReturn(Mono.error(new IllegalStateException("offline")));
        when(port.acknowledge(entry)).thenReturn(Mono.fromRunnable(() -> acknowledged.set(true)));
        StepVerifier.create(new OutboxRelay(port, sender).publish()).expectError(IllegalStateException.class).verify();
        assertFalse(acknowledged.get());
    }

    @Test void configBuildsLocalAndAwsClients() {
        var config = new SQSSenderConfig(); var publisher = mock(MetricPublisher.class);
        try (var local = config.configSqs(new SQSSenderProperties("us-east-1", "queue", "http://localhost:4566"), publisher);
             var aws = config.configSqs(new SQSSenderProperties("us-east-1", "queue", null), publisher);
             var blank = config.configSqs(new SQSSenderProperties("us-east-1", "queue", " "), publisher)) {
            assertEquals("us-east-1", local.serviceClientConfiguration().region().id()); assertNotNull(aws);
            assertNotNull(blank);
        }
    }
}
