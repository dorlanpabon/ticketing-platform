package com.ticketing.orders.sqs.sender;

import com.ticketing.orders.sqs.sender.utils.SqsConstants;

import com.ticketing.orders.model.gateways.OutboxPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Polls for pending outbox messages and hands them off to SQS.
 */
@Component("outboxRelay")
@ConditionalOnProperty(name = SqsConstants.WORKERS_ENABLED, havingValue = SqsConstants.ENABLED)
public final class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxPort port;
    private final OutboxDispatcher dispatcher;

    OutboxRelay(OutboxPort port, OutboxDispatcher dispatcher) {
        this.port = port;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = SqsConstants.OUTBOX_POLL_INTERVAL)
    public Mono<Void> publish() {
        return Mono.defer(() -> port.claim()
            .doOnNext(entry -> LOG.atInfo().addKeyValue(SqsConstants.METRIC, SqsConstants.OUTBOX_AGE_SECONDS)
                .addKeyValue(SqsConstants.METRIC_VALUE,
                    Math.max(0, Duration.between(entry.envelope().occurredAt(), Instant.now()).toSeconds()))
                .log("Outbox publication attempt"))
            .flatMap(entry -> dispatcher.publish(Mono.just(entry)), 8).then());
    }
}
