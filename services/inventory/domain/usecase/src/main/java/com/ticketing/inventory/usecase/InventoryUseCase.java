package com.ticketing.inventory.usecase;

import com.ticketing.inventory.model.*;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

public final class InventoryUseCase {
    private static final String EXPIRED = "RESERVATION_EXPIRED";
    private static final String UNAVAILABLE = "TICKETS_UNAVAILABLE";
    private static final String CONCURRENT = "CONCURRENT_TRANSITION";
    private static final String NOT_FOUND = "RESERVATION_NOT_FOUND";
    private final InventoryStore store;
    private final Clock clock;

    public InventoryUseCase(InventoryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Mono<Reservation> reserve(ReservationRequest request) {
        return Mono.defer(() -> {
            validate(request);
            return current(request.orderId())
                    .flatMap(existing -> matching(existing, request))
                    .switchIfEmpty(Mono.defer(() -> {
                        if (!clock.instant().isBefore(request.expiresAt())) {
                            return store.reject(request, EXPIRED);
                        }
                        return reclaim(request.eventId(), request.ticketIds()).then(Mono.defer(() -> {
                            if (!clock.instant().isBefore(request.expiresAt())) return store.reject(request, EXPIRED);
                            return store.reserve(request);
                        })).onErrorResume(InventoryException.class, error -> {
                            if (UNAVAILABLE.equals(error.code()) || EXPIRED.equals(error.code())) {
                                return store.reject(request, error.code());
                            }
                            return Mono.error(error);
                        });
                    }));
        });
    }

    private Mono<Reservation> matching(Reservation reservation, ReservationRequest request) {
        return reservation.matches(request) ? Mono.just(reservation)
                : Mono.error(new InventoryException("IDEMPOTENCY_CONFLICT", 409));
    }

    public Mono<Reservation> get(String orderId) {
        return current(orderId).switchIfEmpty(Mono.error(new InventoryException(NOT_FOUND, 404)));
    }

    private Mono<Reservation> current(String orderId) {
        return Mono.defer(() -> store.find(orderId).flatMap(this::expireIfDue))
                .retryWhen(Retry.max(2).filter(error -> error instanceof InventoryException failure
                        && CONCURRENT.equals(failure.code())).onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private Mono<Reservation> expireIfDue(Reservation reservation) {
        return reservation.status().isHeld() && reservation.expiredAt(clock.instant())
                ? expire(reservation) : Mono.just(reservation);
    }

    private Mono<Void> reclaim(String eventId, List<String> ticketIds) {
        return store.holders(eventId, ticketIds).flatMap(holder -> current(holder.orderId()), 8).then();
    }

    public Mono<Reservation> confirm(String orderId, String customerId, String commandId) {
        return Mono.defer(() -> {
            requireIdentifier(commandId);
            return store.find(orderId).switchIfEmpty(Mono.error(new InventoryException(NOT_FOUND, 404)))
                    .flatMap(reservation -> confirmCurrent(reservation, customerId, commandId));
        }).onErrorResume(InventoryException.class, error -> {
            if (!CONCURRENT.equals(error.code())) return Mono.error(error);
            return get(orderId).flatMap(current -> {
                if (current.status() == TicketStatus.SOLD || !current.status().isHeld()) return Mono.just(current);
                return Mono.error(error);
            });
        });
    }

    private Mono<Reservation> confirmCurrent(Reservation reservation, String customerId, String commandId) {
        if (!reservation.customerId().equals(customerId)) {
            return Mono.error(new InventoryException(NOT_FOUND, 404));
        }
        if (reservation.status() == TicketStatus.SOLD) return Mono.just(reservation);
        if (!reservation.status().isHeld()) {
            return Mono.error(new InventoryException(EXPIRED.equals(reservation.resolution()) ? EXPIRED : "INVALID_TRANSITION", 409));
        }
        if (reservation.expiredAt(clock.instant())) return expire(reservation);
        if (reservation.status() == TicketStatus.PENDING_CONFIRMATION) {
            return commandId.equals(reservation.confirmationId()) ? sell(reservation, commandId)
                    : Mono.error(new InventoryException("CONFIRMATION_CONFLICT", 409));
        }
        return store.transition(reservation, TicketStatus.PENDING_CONFIRMATION, "NONE", commandId, clock.instant())
                .flatMap(pending -> sell(pending, commandId));
    }

    private Mono<Reservation> sell(Reservation reservation, String commandId) {
        return Mono.defer(() -> reservation.expiredAt(clock.instant()) ? expire(reservation)
                : store.transition(reservation, TicketStatus.SOLD, "NONE", commandId, clock.instant()));
    }

    private Mono<Reservation> expire(Reservation reservation) {
        return store.transition(reservation, TicketStatus.AVAILABLE, EXPIRED, null, clock.instant());
    }

    public Mono<Long> expireDue(int limit) {
        return expireDue(limit, ignored -> { });
    }

    public Mono<Long> expireDue(int limit, java.util.function.LongConsumer observedLagSeconds) {
        return store.expired(clock.instant(), limit).buffer(limit).concatMap(due -> {
            long lag = due.stream().mapToLong(reservation -> Math.max(0,
                    Duration.between(reservation.expiresAt(), clock.instant()).toSeconds())).max().orElse(0);
            observedLagSeconds.accept(lag);
            return Flux.fromIterable(due)
                .flatMap(reservation -> expire(reservation).onErrorResume(InventoryException.class, error ->
                        CONCURRENT.equals(error.code()) ? Mono.empty() : Mono.error(error)), 8)
                .count();
        }, 1).switchIfEmpty(Mono.fromSupplier(() -> {
            observedLagSeconds.accept(0);
            return 0L;
        })).reduce(0L, Long::sum);
    }

    public Mono<Reservation> expireReservation(String orderId, java.time.Instant deadline) {
        return expireReservation(orderId, deadline, ignored -> { });
    }

    public Mono<Reservation> expireReservation(String orderId, java.time.Instant deadline,
                                                java.util.function.LongConsumer observedLagSeconds) {
        return Mono.defer(() -> store.find(orderId)
                .switchIfEmpty(Mono.error(new InventoryException(NOT_FOUND, 404)))
                .flatMap(reservation -> {
                    if (!reservation.expiresAt().equals(deadline)) return Mono.error(new InventoryException("EXPIRY_DEADLINE_MISMATCH", 409));
                    if (reservation.status().isHeld() && !reservation.expiredAt(clock.instant())) {
                        return Mono.error(new InventoryException("RESERVATION_NOT_DUE", 409));
                    }
                    if (reservation.status().isHeld()) observedLagSeconds.accept(Duration.between(deadline, clock.instant()).toSeconds());
                    return current(orderId);
                }));
    }

    public Mono<Long> recoverExpired(int batchSize, java.util.function.LongConsumer observedLagSeconds) {
        return Mono.defer(() -> {
            String owner = java.util.UUID.randomUUID().toString();
            return Mono.usingWhen(store.claimRecovery(owner, clock.instant()).filter(WorkLease::acquired),
                    lease -> expireDue(batchSize, observedLagSeconds).timeout(Duration.ofSeconds(45)),
                    lease -> store.releaseRecovery(owner),
                    (lease, error) -> store.releaseRecovery(owner),
                    lease -> store.releaseRecovery(owner)).defaultIfEmpty(0L);
        });
    }

    public Mono<Void> provision(String eventId, int capacity) {
        return Mono.defer(() -> {
            requireIdentifier(eventId);
            if (capacity < 1 || capacity > 100_000) throw new InventoryException("INVALID_CAPACITY", 400);
            return store.provision(eventId, capacity);
        });
    }

    public Mono<Availability> availability(String eventId) { return store.availability(eventId); }

    public Mono<TicketPage> tickets(String eventId, int limit, String cursor) {
        if (limit < 1 || limit > 100) return Mono.error(new InventoryException("INVALID_PAGE_SIZE", 400));
        return store.tickets(eventId, limit, cursor).flatMap(page -> {
            var held = page.items().stream().filter(ticket -> ticket.status().isHeld()).map(Ticket::ticketId).toList();
            if (held.isEmpty()) return Mono.just(page);
            return reclaim(eventId, held).then(Mono.defer(() -> store.tickets(eventId, limit, cursor)));
        });
    }

    private void validate(ReservationRequest request) {
        requireIdentifier(request.orderId());
        requireIdentifier(request.eventId());
        requireIdentifier(request.customerId());
        validateTickets(request);
        validateDeadline(request);
        validatePurchaseType(request);
    }

    private static void validateTickets(ReservationRequest request) {
        if (request.ticketIds().isEmpty() || request.ticketIds().size() > 10
                || new HashSet<>(request.ticketIds()).size() != request.ticketIds().size()
                || request.ticketIds().stream().anyMatch(id -> !id.matches("[1-9][0-9]{0,5}") || Integer.parseInt(id) > 100_000)) {
            throw new InventoryException("INVALID_TICKETS", 400);
        }
    }

    private void validateDeadline(ReservationRequest request) {
        if (request.requestedAt() == null || request.expiresAt() == null
                || !request.expiresAt().isAfter(request.requestedAt())
                || Duration.between(request.requestedAt(), request.expiresAt()).compareTo(Duration.ofSeconds(600)) > 0
                || request.requestedAt().isAfter(clock.instant().plusSeconds(2))) {
            throw new InventoryException("INVALID_DEADLINE", 400);
        }
    }

    private static void validatePurchaseType(ReservationRequest request) {
        if (!"PURCHASE".equals(request.type()) && !request.complimentary()) {
            throw new InventoryException("INVALID_PURCHASE_TYPE", 400);
        }
        if (request.complimentary()) validateComplimentary(request);
    }

    private static void validateComplimentary(ReservationRequest request) {
        if (request.actorId() == null || request.actorId().isBlank()
                || request.reason() == null || request.reason().isBlank() || request.reason().length() > 500) {
            throw new InventoryException("INVALID_COMPLIMENTARY_REASON", 400);
        }
    }

    private static void requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9:_-]{1,200}")) {
            throw new InventoryException("INVALID_IDENTIFIER", 400);
        }
    }
}
