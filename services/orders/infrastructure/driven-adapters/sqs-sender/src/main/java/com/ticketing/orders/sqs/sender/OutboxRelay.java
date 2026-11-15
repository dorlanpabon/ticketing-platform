package com.ticketing.orders.sqs.sender;

import com.ticketing.orders.model.gateways.OutboxPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.json.JsonMapper;
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "workers.enabled", havingValue = "true")
public final class OutboxRelay {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxPort port;
    private final SQSSender sender;
    private final JsonMapper mapper=JsonMapper.builder().build();
    public OutboxRelay(OutboxPort port,SQSSender sender) { this.port=port; this.sender=sender; }
    @Scheduled(fixedDelayString="${OUTBOX_POLL_INTERVAL_MS:500}")
    public Mono<Void> publish() {
        return Mono.defer(() -> port.claim().doOnNext(entry -> LOG.atInfo()
                .addKeyValue("metric", "outbox_age_seconds")
                .addKeyValue("value", Math.max(0, java.time.Duration.between(entry.envelope().occurredAt(), java.time.Instant.now()).toSeconds()))
                .log("Outbox publication attempt"))
                .flatMap(entry -> sender.send(mapper.writeValueAsString(entry.envelope()))
                .then(Mono.defer(() -> port.acknowledge(entry))),8).then());
    }
}
