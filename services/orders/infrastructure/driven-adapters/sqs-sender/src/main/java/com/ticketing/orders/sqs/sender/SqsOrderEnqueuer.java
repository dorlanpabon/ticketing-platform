package com.ticketing.orders.sqs.sender;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.gateways.OrderEnqueuePort;
import com.ticketing.orders.model.gateways.OutboxPort;
import com.ticketing.orders.model.utils.ErrorCodes;
import com.ticketing.orders.sqs.sender.config.SqsEnqueueProperties;
import com.ticketing.orders.sqs.sender.utils.SqsConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Waits for SQS acknowledgement so the API can confirm that an order was queued.
 */
@Component
final class SqsOrderEnqueuer implements OrderEnqueuePort {
    private static final Logger LOG = LoggerFactory.getLogger(SqsOrderEnqueuer.class);
    private final OutboxPort port;
    private final OutboxDispatcher dispatcher;
    private final SqsEnqueueProperties properties;

    SqsOrderEnqueuer(OutboxPort port, OutboxDispatcher dispatcher, SqsEnqueueProperties properties) {
        this.port = port;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public Mono<Void> enqueue(String messageId) {
        return dispatcher.publish(Mono.defer(() -> port.claim(messageId))).map(entry -> true)
            .switchIfEmpty(Mono.defer(() -> port.pending(messageId)).map(pending -> !pending))
            .filter(Boolean::booleanValue)
            .repeatWhenEmpty(attempts -> attempts.delayElements(properties.retryDelay()))
            .timeout(properties.timeout()).onErrorMap(error -> {
                LOG.atWarn().addKeyValue(SqsConstants.MESSAGE_ID, messageId)
                    .addKeyValue("errorType", error.getClass().getSimpleName()).log("Order enqueue failed");
                return new DomainFailure(503, ErrorCodes.ORDER_ENQUEUE_UNAVAILABLE);
            }).then();
    }
}
