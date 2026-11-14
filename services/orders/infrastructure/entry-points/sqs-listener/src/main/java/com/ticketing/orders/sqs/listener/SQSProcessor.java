package com.ticketing.orders.sqs.listener;

import com.ticketing.orders.model.*;
import com.ticketing.orders.usecase.OrdersUseCase;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;
import java.util.function.Function;
@Service
public final class SQSProcessor implements Function<Message,Mono<Void>> {
    private final OrdersUseCase useCase;
    private final JsonMapper mapper=JsonMapper.builder().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public SQSProcessor(OrdersUseCase useCase) { this.useCase=useCase; }
    @Override public Mono<Void> apply(Message message) {
        return Mono.defer(() -> {
            Envelope envelope=mapper.readValue(message.body(),Envelope.class);
            return useCase.receive(envelope,mapper.convertValue(envelope.payload(),Reservation.class));
        });
    }
}
