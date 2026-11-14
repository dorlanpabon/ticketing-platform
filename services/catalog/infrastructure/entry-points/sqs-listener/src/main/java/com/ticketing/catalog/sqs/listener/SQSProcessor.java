package com.ticketing.catalog.sqs.listener;

import com.ticketing.catalog.model.*;
import com.ticketing.catalog.usecase.CatalogUseCase;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;
import java.util.function.Function;
@Service
public final class SQSProcessor implements Function<Message,Mono<Void>> {
    private final CatalogUseCase useCase;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public SQSProcessor(CatalogUseCase useCase) { this.useCase=useCase; }
    @Override public Mono<Void> apply(Message message) {
        return Mono.defer(() -> {
            Envelope envelope=mapper.readValue(message.body(),Envelope.class);
            return useCase.receive(envelope);
        });
    }
}
