package com.ticketing.catalog.sqs.sender;

import com.ticketing.catalog.model.gateways.OutboxPort;
import com.ticketing.catalog.sqs.sender.utils.PublisherConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * Polls for pending outbox messages and hands them off to SQS.
 */
@Component
@ConditionalOnProperty(name = "WORKERS_ENABLED", havingValue = "true")
public final class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxPort port;
    private final SqsCommandPublisher sender;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OutboxRelay(OutboxPort port, SqsCommandPublisher sender) {
        this.port = port;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "${OUTBOX_POLL_INTERVAL_MS:500}")
    public Mono<Void> publish() {
        return Mono.defer(() -> port.claim()
            .doOnNext(entry -> LOG.atInfo()
                .addKeyValue(PublisherConstants.METRIC_KEY, PublisherConstants.OUTBOX_AGE_METRIC)
                .addKeyValue(PublisherConstants.METRIC_VALUE_KEY,
                    Math.max(0, Duration.between(entry.envelope().occurredAt(), Instant.now()).toSeconds()))
                .log(PublisherConstants.OUTBOX_ATTEMPT_LOG))
            .flatMap(entry -> sender.send(mapper.writeValueAsString(entry.envelope()))
                .then(port.acknowledge(entry)), 8)
            .then());
    }
}
