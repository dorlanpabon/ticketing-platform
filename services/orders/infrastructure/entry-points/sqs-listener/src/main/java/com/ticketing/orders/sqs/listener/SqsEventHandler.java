package com.ticketing.orders.sqs.listener;

import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.sqs.listener.dto.ReservationStatePayload;
import com.ticketing.orders.sqs.listener.mapper.ReservationPayloadMapper;
import com.ticketing.orders.usecase.OrdersUseCase;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.sqs.model.Message;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.function.Function;

/**
 * Applies reservation state events from Inventory to Orders.
 */
@Service("SQSProcessor")
public final class SqsEventHandler implements Function<Message, Mono<Void>> {
    private final OrdersUseCase useCase;
    private final ReservationPayloadMapper payloadMapper;
    private final JsonMapper jsonMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public SqsEventHandler(OrdersUseCase useCase, ReservationPayloadMapper payloadMapper) {
        this.useCase = useCase;
        this.payloadMapper = payloadMapper;
    }

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.defer(() -> {
            Envelope envelope = jsonMapper.readValue(message.body(), Envelope.class);
            ReservationStatePayload payload = jsonMapper.convertValue(envelope.payload(),
                ReservationStatePayload.class);
            return useCase.receive(envelope, payloadMapper.toDomain(payload));
        });
    }
}
