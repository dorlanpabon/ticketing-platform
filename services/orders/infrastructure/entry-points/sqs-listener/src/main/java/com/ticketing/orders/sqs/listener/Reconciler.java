package com.ticketing.orders.sqs.listener;

import com.ticketing.orders.sqs.listener.utils.SqsConstants;

import com.ticketing.orders.usecase.OrdersUseCase;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Refreshes pending orders using authoritative reservation state from Inventory.
 */
@Component("orderReconciler")
@ConditionalOnProperty(name = SqsConstants.WORKERS_ENABLED, havingValue = SqsConstants.ENABLED)
public final class Reconciler {
    private final OrdersUseCase useCase;

    public Reconciler(OrdersUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = SqsConstants.RECONCILIATION_INTERVAL)
    public Mono<Void> reconcile() {
        return Mono.defer(useCase::reconcile);
    }
}
