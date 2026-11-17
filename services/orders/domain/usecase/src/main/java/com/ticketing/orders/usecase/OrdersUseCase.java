package com.ticketing.orders.usecase;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.PurchaseCommand;
import com.ticketing.orders.model.Reservation;
import com.ticketing.orders.model.Validation;
import com.ticketing.orders.model.gateways.InventoryPort;
import com.ticketing.orders.model.gateways.OrderEnqueuePort;
import com.ticketing.orders.model.gateways.OrdersPort;
import com.ticketing.orders.model.utils.ErrorCodes;
import com.ticketing.orders.model.utils.OrderProtocol;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates purchases, complimentary orders and confirmation with inventory.
 */
public final class OrdersUseCase {
    private final OrdersPort port;
    private final InventoryPort inventory;
    private final Clock clock;
    private final OrderEnqueuePort enqueue;

    public OrdersUseCase(OrdersPort port, InventoryPort inventory, Clock clock, OrderEnqueuePort enqueue) {
        this.port = port;
        this.inventory = inventory;
        this.clock = clock;
        this.enqueue = enqueue;
    }

    public Mono<Order> create(String customer, String key, String eventId, List<String> tickets, String actor,
        String reason, boolean complimentary) {
        return Mono.defer(() -> {
            Order order = OrderCreation.prepare(customer, key, eventId, tickets, actor, reason, complimentary,
                clock);
            String fingerprint = OrderCreation.fingerprint(order);
            return port.create(order, OrderCreation.idempotencyKey(order, key), fingerprint,
                PurchaseCommand.from(order)).flatMap(
                    submission -> enqueue.enqueue(submission.messageId()).thenReturn(submission.order()));
        });
    }

    public Mono<Order> get(String customer, String id) {
        return owned(customer, id).flatMap(order -> inventory.find(id).map(order::withInventory)
            .switchIfEmpty(order.inventoryVersion() == 0 ? Mono.just(order)
                : Mono.error(new DomainFailure(503, ErrorCodes.INVENTORY_STATE_MISSING))));
    }

    public Mono<Order> confirm(String customer, String id, String key) {
        return Mono.defer(() -> {
            Validation.key(key);
            return get(customer, id).flatMap(order -> {
                boolean sold = order.status().equals(Reservation.SOLD);
                if (!sold && !clock.instant().isBefore(order.expiresAt()))
                    return Mono.error(new DomainFailure(410, ErrorCodes.RESERVATION_EXPIRED));
                if (!sold && !Set.of(Reservation.RESERVED, Reservation.PENDING_CONFIRMATION)
                    .contains(order.status()))
                    return Mono.error(new DomainFailure(409, ErrorCodes.RESERVATION_NOT_READY));
                String commandId = OrderProtocol.CONFIRM_PREFIX + id;
                Envelope message = new Envelope(commandId, OrderProtocol.CONFIRM_PURCHASE,
                    OrderProtocol.SCHEMA_VERSION, id, id, id, clock.instant(), Map.of(OrderProtocol.ORDER_ID,
                        id, OrderProtocol.CUSTOMER_ID, customer, OrderProtocol.COMMAND_ID, commandId));
                return port.confirm(order,
                    Validation.hash(customer + OrderProtocol.CONFIRM_KEY_SEPARATOR + key), message)
                    .thenReturn(order);
            });
        });
    }

    private Mono<Order> owned(String customer, String id) {
        return port.find(id).filter(order -> order.customerId().equals(customer))
            .switchIfEmpty(Mono.error(new DomainFailure(404, ErrorCodes.ORDER_NOT_FOUND)));
    }

    public Mono<Void> receive(Envelope envelope, Reservation state) {
        if (envelope.schemaVersion() != OrderProtocol.SCHEMA_VERSION
            || !OrderProtocol.RESERVATION_STATE_CHANGED.equals(envelope.type())
            || envelope.messageId() == null || envelope.messageId().isBlank()
            || !state.orderId().equals(envelope.aggregateId()))
            return Mono.error(new DomainFailure(400, ErrorCodes.UNSUPPORTED_MESSAGE));
        return port.project(state, envelope.messageId());
    }

    public Mono<Void> reconcile() {
        return port.due()
            .flatMap(order -> inventory.find(order.orderId())
                .switchIfEmpty(Mono.defer(() -> inventory.reserve(order)))
                .flatMap(state -> port.project(state,
                    OrderProtocol.RECONCILIATION_PREFIX + order.orderId() + OrderProtocol.VERSION_SEPARATOR
                        + state.inventoryVersion()))
                .then(Mono.defer(() -> port.reviewed(order)))
                .onErrorResume(error -> port.reviewFailed(order)), 8)
            .then();
    }

    public static Map<String, Object> payload(Order order) {
        return PurchaseCommand.payload(order);
    }
}
