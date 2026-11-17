package com.ticketing.catalog.sqs.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.catalog.model.Envelope;
import com.ticketing.catalog.usecase.CatalogUseCase;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import software.amazon.awssdk.services.sqs.model.Message;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;

class SqsEventProcessorTest {
    @Test
    void validEnvelopeReachesUseCaseAndMalformedBodyFails() {
        CatalogUseCase useCase = mock(CatalogUseCase.class);
        Envelope envelope = Envelope.create("InventoryReady", "e", Map.of("eventId", "e"), Instant.EPOCH);
        when(useCase.receive(envelope)).thenReturn(Mono.empty());
        SqsEventProcessor processor = new SqsEventProcessor(useCase);
        StepVerifier
            .create(processor.apply(
                Message.builder().body(JsonMapper.builder().build().writeValueAsString(envelope)).build()))
            .verifyComplete();
        verify(useCase).receive(envelope);
        StepVerifier.create(processor.apply(Message.builder().body("{").build())).expectError().verify();
        verifyNoMoreInteractions(useCase);
    }
}
