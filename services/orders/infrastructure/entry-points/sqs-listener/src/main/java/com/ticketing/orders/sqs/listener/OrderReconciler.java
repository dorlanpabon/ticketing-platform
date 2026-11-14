package com.ticketing.orders.sqs.listener;

import com.ticketing.orders.usecase.OrdersUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "workers.enabled", havingValue = "true")
public final class OrderReconciler {
    private final OrdersUseCase useCase;
    public OrderReconciler(OrdersUseCase useCase) { this.useCase=useCase; }
    @Scheduled(fixedDelayString="${RECONCILIATION_INTERVAL_MS:5000}")
    public Mono<Void> reconcile() { return Mono.defer(useCase::reconcile); }
}
