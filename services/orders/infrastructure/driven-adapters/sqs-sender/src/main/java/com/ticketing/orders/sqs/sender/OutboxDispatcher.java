package com.ticketing.orders.sqs.sender;

import com.ticketing.orders.model.OutboxEntry;
import com.ticketing.orders.model.gateways.OutboxPort;
import com.ticketing.orders.sqs.sender.utils.SqsConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes claimed order commands and records delivery or retry in the outbox.
 */
@Component
final class OutboxDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxPort port;
    private final SqsCommandPublisher sender;
    private final JsonMapper mapper = JsonMapper.builder().build();

    OutboxDispatcher(OutboxPort port, SqsCommandPublisher sender) {
        this.port = port;
        this.sender = sender;
    }

    Mono<OutboxEntry> publish(Mono<OutboxEntry> claim) {
        return Mono.usingWhen(claim,
            entry -> Mono.defer(() -> sender.send(mapper.writeValueAsString(entry.envelope())))
                .then(Mono.defer(() -> port.acknowledge(entry))).thenReturn(entry),
            entry -> Mono.empty(), (entry, error) -> release(entry), this::release);
    }

    private Mono<Void> release(OutboxEntry entry) {
        return Mono.defer(() -> port.release(entry)).onErrorResume(error -> {
            LOG.atWarn().addKeyValue(SqsConstants.MESSAGE_ID, entry.envelope().messageId())
                .addKeyValue("errorType", error.getClass().getSimpleName())
                .log("Outbox lease release failed");
            return Mono.empty();
        });
    }
}
