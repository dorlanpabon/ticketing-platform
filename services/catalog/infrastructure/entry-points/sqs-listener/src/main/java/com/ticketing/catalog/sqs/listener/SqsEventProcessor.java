package com.ticketing.catalog.sqs.listener;

import com.ticketing.catalog.model.Envelope;
import com.ticketing.catalog.usecase.CatalogUseCase;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.sqs.model.Message;

import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;

/**
 * Applies inventory-ready events to the catalog.
 */
@Service
public final class SqsEventProcessor implements Function<Message, Mono<Void>> {
    private final CatalogUseCase useCase;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public SqsEventProcessor(CatalogUseCase useCase) {
        this.useCase = useCase;
    }

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.defer(() -> {
            Envelope envelope = mapper.readValue(message.body(), Envelope.class);
            return useCase.receive(envelope);
        });
    }
}
