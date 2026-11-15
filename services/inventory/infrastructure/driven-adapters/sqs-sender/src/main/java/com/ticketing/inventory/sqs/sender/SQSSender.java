package com.ticketing.inventory.sqs.sender;

import com.ticketing.inventory.model.MessagePublisher;
import com.ticketing.inventory.model.OutboxMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;

public final class SQSSender implements MessagePublisher {
    private static final String EXPIRY_COMMAND = "ExpireReservation";
    private final String orderQueue;
    private final String catalogQueue;
    private final String expirationsQueue;
    private final SqsAsyncClient client;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public SQSSender(SqsAsyncClient client, String orderQueue, String catalogQueue, String expirationsQueue, Clock clock) {
        this.client = client;
        this.orderQueue = orderQueue;
        this.catalogQueue = catalogQueue;
        this.expirationsQueue = expirationsQueue;
        this.clock = clock;
    }

    @Override public Mono<Void> publish(OutboxMessage message) {
        String queue = switch (message.destination()) {
            case "ORDERS" -> orderQueue;
            case "CATALOG" -> catalogQueue;
            case "EXPIRATIONS" -> expirationsQueue;
            default -> throw new IllegalArgumentException("Unknown outbox destination");
        };
        return Mono.defer(() -> {
            var envelope = json.readTree(message.body());
            Instant occurredAt = Instant.parse(envelope.get("occurredAt").asText());
            long age = Math.max(0, Duration.between(occurredAt, clock.instant()).toSeconds());
            org.slf4j.LoggerFactory.getLogger(SQSSender.class).atInfo().addKeyValue("metric", "outbox_age_seconds")
                    .addKeyValue("value", age).log("Outbox publication age");
            return Mono.fromFuture(() -> {
                int delay = 0;
                if (EXPIRY_COMMAND.equals(envelope.path("type").asText())) {
                    Instant deadline = Instant.parse(envelope.path("payload").path("expiresAt").asText());
                    var remaining = Duration.between(clock.instant(), deadline);
                    delay = (int) Math.min(600, Math.max(0, remaining.toSeconds() + (remaining.getNano() > 0 ? 1 : 0)));
                }
                return client.sendMessage(SendMessageRequest.builder().queueUrl(queue)
                        .messageBody(message.body()).delaySeconds(delay).build());
            }).then();
        });
    }
}
