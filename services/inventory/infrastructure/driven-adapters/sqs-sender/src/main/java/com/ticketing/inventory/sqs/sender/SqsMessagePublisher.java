package com.ticketing.inventory.sqs.sender;

import com.ticketing.inventory.sqs.sender.utils.SqsSenderConstants;
import org.slf4j.Logger;
import com.ticketing.inventory.model.MessagePublisher;
import com.ticketing.inventory.model.OutboxMessage;

import org.slf4j.LoggerFactory;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class SqsMessagePublisher implements MessagePublisher {
    private static final Logger LOG = LoggerFactory.getLogger(SqsMessagePublisher.class);

    private final String orderQueue;
    private final String catalogQueue;
    private final String expirationsQueue;
    private final SqsAsyncClient client;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public SqsMessagePublisher(SqsAsyncClient client, String orderQueue, String catalogQueue,
        String expirationsQueue, Clock clock) {
        this.client = client;
        this.orderQueue = orderQueue;
        this.catalogQueue = catalogQueue;
        this.expirationsQueue = expirationsQueue;
        this.clock = clock;
    }

    @Override
    public Mono<Void> publish(OutboxMessage message) {
        String queue = switch (message.destination()) {
        case SqsSenderConstants.ORDERS_DESTINATION -> orderQueue;
        case SqsSenderConstants.CATALOG_DESTINATION -> catalogQueue;
        case SqsSenderConstants.EXPIRATIONS_DESTINATION -> expirationsQueue;
        default -> throw new IllegalArgumentException(SqsSenderConstants.UNKNOWN_DESTINATION);
        };
        return Mono.defer(() -> {
            var envelope = json.readTree(message.body());
            Instant occurredAt = Instant.parse(envelope.get(SqsSenderConstants.OCCURRED_AT).asText());
            long age = Math.max(0, Duration.between(occurredAt, clock.instant()).toSeconds());
            LOG.atInfo().addKeyValue(SqsSenderConstants.METRIC, SqsSenderConstants.OUTBOX_AGE_SECONDS)
                .addKeyValue(SqsSenderConstants.VALUE, age).log(SqsSenderConstants.OUTBOX_AGE_MESSAGE);
            return Mono.fromFuture(() -> {
                int delay = 0;
                if (SqsSenderConstants.EXPIRY_COMMAND
                    .equals(envelope.path(SqsSenderConstants.TYPE).asText())) {
                    Instant deadline = Instant.parse(envelope.path(SqsSenderConstants.PAYLOAD)
                        .path(SqsSenderConstants.EXPIRES_AT).asText());
                    var remaining = Duration.between(clock.instant(), deadline);
                    delay = (int) Math.min(600,
                        Math.max(0, remaining.toSeconds() + (remaining.getNano() > 0 ? 1 : 0)));
                }
                return client.sendMessage(SendMessageRequest.builder().queueUrl(queue)
                    .messageBody(message.body()).delaySeconds(delay).build());
            }).then();
        });
    }
}
