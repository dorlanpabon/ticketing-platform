package com.ticketing.inventory.usecase;

import com.ticketing.inventory.model.Availability;
import com.ticketing.inventory.model.InventoryErrorCodes;
import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.InventoryExecutionPolicy;
import com.ticketing.inventory.model.InventoryRules;
import com.ticketing.inventory.model.InventoryStore;
import com.ticketing.inventory.model.Reservation;
import com.ticketing.inventory.model.ReservationIntent;
import com.ticketing.inventory.model.Ticket;
import com.ticketing.inventory.model.TicketPage;
import com.ticketing.inventory.model.TicketStatus;
import com.ticketing.inventory.model.WorkLease;
import com.ticketing.inventory.usecase.validation.ReservationValidator;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Coordinates ticket provisioning, availability and reservation transitions.
 */
public final class InventoryUseCase {

    private final InventoryStore store;
    private final Clock clock;
    private final ReservationValidator validator;
    private final InventoryExecutionPolicy executionPolicy;

    public InventoryUseCase(InventoryStore store, Clock clock, InventoryExecutionPolicy executionPolicy) {
        this.store = store;
        this.clock = clock;
        this.validator = new ReservationValidator(clock);
        this.executionPolicy = executionPolicy;
    }

    public Mono<Reservation> reserve(ReservationIntent request) {
        return Mono.defer(() -> {
            validator.validate(request);
            return current(request.orderId()).flatMap(existing -> matching(existing, request))
                .switchIfEmpty(Mono.defer(() -> {
                    if (!clock.instant().isBefore(request.expiresAt())) {
                        return store.reject(request, InventoryErrorCodes.RESERVATION_EXPIRED);
                    }
                    return reclaim(request.eventId(), request.ticketIds()).then(Mono.defer(() -> {
                        if (!clock.instant().isBefore(request.expiresAt()))
                            return store.reject(request, InventoryErrorCodes.RESERVATION_EXPIRED);
                        return store.reserve(request);
                    })).onErrorResume(InventoryException.class, error -> {
                        if (InventoryErrorCodes.TICKETS_UNAVAILABLE.equals(error.code())
                            || InventoryErrorCodes.RESERVATION_EXPIRED.equals(error.code())) {
                            return store.reject(request, error.code());
                        }
                        return Mono.error(error);
                    });
                }));
        });
    }

    private Mono<Reservation> matching(Reservation reservation, ReservationIntent request) {
        return reservation.matches(request) ? Mono.just(reservation)
            : Mono.error(new InventoryException(InventoryErrorCodes.IDEMPOTENCY_CONFLICT, 409));
    }

    public Mono<Reservation> get(String orderId) {
        return current(orderId).switchIfEmpty(
            Mono.error(new InventoryException(InventoryErrorCodes.RESERVATION_NOT_FOUND, 404)));
    }

    private Mono<Reservation> current(String orderId) {
        return Mono.defer(() -> store.find(orderId).flatMap(this::expireIfDue))
            .retryWhen(Retry.max(executionPolicy.transitionRetries())
                .filter(error -> error instanceof InventoryException failure
                    && InventoryErrorCodes.CONCURRENT_TRANSITION.equals(failure.code()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private Mono<Reservation> expireIfDue(Reservation reservation) {
        return reservation.status().isHeld() && reservation.expiredAt(clock.instant()) ? expire(reservation)
            : Mono.just(reservation);
    }

    private Mono<Void> reclaim(String eventId, List<String> ticketIds) {
        return store.holders(eventId, ticketIds)
            .flatMap(holder -> current(holder.orderId()), executionPolicy.processingConcurrency()).then();
    }

    public Mono<Reservation> confirm(String orderId, String customerId, String commandId) {
        return Mono.defer(() -> {
            validator.requireIdentifier(commandId);
            return store.find(orderId)
                .switchIfEmpty(
                    Mono.error(new InventoryException(InventoryErrorCodes.RESERVATION_NOT_FOUND, 404)))
                .flatMap(reservation -> confirmCurrent(reservation, customerId, commandId));
        }).onErrorResume(InventoryException.class, error -> {
            if (!InventoryErrorCodes.CONCURRENT_TRANSITION.equals(error.code()))
                return Mono.error(error);
            return get(orderId).flatMap(current -> {
                if (current.status() == TicketStatus.SOLD || !current.status().isHeld())
                    return Mono.just(current);
                return Mono.error(error);
            });
        });
    }

    private Mono<Reservation> confirmCurrent(Reservation reservation, String customerId, String commandId) {
        if (!reservation.customerId().equals(customerId)) {
            return Mono.error(new InventoryException(InventoryErrorCodes.RESERVATION_NOT_FOUND, 404));
        }
        if (reservation.status() == TicketStatus.SOLD)
            return Mono.just(reservation);
        if (!reservation.status().isHeld()) {
            return Mono.error(new InventoryException(
                InventoryErrorCodes.RESERVATION_EXPIRED.equals(reservation.resolution())
                    ? InventoryErrorCodes.RESERVATION_EXPIRED
                    : InventoryErrorCodes.INVALID_TRANSITION,
                409));
        }
        if (reservation.expiredAt(clock.instant()))
            return expire(reservation);
        if (reservation.status() == TicketStatus.PENDING_CONFIRMATION) {
            return commandId.equals(reservation.confirmationId()) ? sell(reservation, commandId)
                : Mono.error(new InventoryException(InventoryErrorCodes.CONFIRMATION_CONFLICT, 409));
        }
        return store.transition(reservation, TicketStatus.PENDING_CONFIRMATION, InventoryRules.NO_RESOLUTION,
            commandId, clock.instant()).flatMap(pending -> sell(pending, commandId));
    }

    private Mono<Reservation> sell(Reservation reservation, String commandId) {
        return Mono.defer(() -> reservation.expiredAt(clock.instant()) ? expire(reservation)
            : store.transition(reservation, TicketStatus.SOLD, InventoryRules.NO_RESOLUTION, commandId,
                clock.instant()));
    }

    private Mono<Reservation> expire(Reservation reservation) {
        return store.transition(reservation, TicketStatus.AVAILABLE, InventoryErrorCodes.RESERVATION_EXPIRED,
            null, clock.instant());
    }

    public Mono<Long> expireDue(int limit) {
        return expireDue(limit, ignored -> {
        });
    }

    public Mono<Long> expireDue(int limit, LongConsumer observedLagSeconds) {
        return store.expired(clock.instant(), limit).buffer(limit).concatMap(due -> {
            long lag = due.stream().mapToLong(reservation -> Math.max(0,
                Duration.between(reservation.expiresAt(), clock.instant()).toSeconds())).max().orElse(0);
            observedLagSeconds.accept(lag);
            return Flux.fromIterable(due)
                .flatMap(reservation -> expire(reservation).onErrorResume(InventoryException.class,
                    error -> InventoryErrorCodes.CONCURRENT_TRANSITION.equals(error.code()) ? Mono.empty()
                        : Mono.error(error)),
                    executionPolicy.processingConcurrency())
                .count();
        }, 1).switchIfEmpty(Mono.fromSupplier(() -> {
            observedLagSeconds.accept(0);
            return 0L;
        })).reduce(0L, Long::sum);
    }

    public Mono<Reservation> expireReservation(String orderId, Instant deadline) {
        return expireReservation(orderId, deadline, ignored -> {
        });
    }

    public Mono<Reservation> expireReservation(String orderId, Instant deadline,
        LongConsumer observedLagSeconds) {
        return Mono.defer(() -> store.find(orderId)
            .switchIfEmpty(Mono.error(new InventoryException(InventoryErrorCodes.RESERVATION_NOT_FOUND, 404)))
            .flatMap(reservation -> {
                if (!reservation.expiresAt().equals(deadline))
                    return Mono
                        .error(new InventoryException(InventoryErrorCodes.EXPIRY_DEADLINE_MISMATCH, 409));
                if (reservation.status().isHeld() && !reservation.expiredAt(clock.instant())) {
                    return Mono.error(new InventoryException(InventoryErrorCodes.RESERVATION_NOT_DUE, 409));
                }
                if (reservation.status().isHeld())
                    observedLagSeconds.accept(Duration.between(deadline, clock.instant()).toSeconds());
                return current(orderId);
            }));
    }

    public Mono<Long> recoverExpired(int batchSize, LongConsumer observedLagSeconds) {
        return Mono.defer(() -> {
            String owner = UUID.randomUUID().toString();
            return Mono
                .usingWhen(store.claimRecovery(owner, clock.instant()).filter(WorkLease::acquired),
                    lease -> expireDue(batchSize, observedLagSeconds)
                        .timeout(InventoryRules.RECOVERY_SWEEP_BUDGET),
                    lease -> store.releaseRecovery(owner), (lease, error) -> store.releaseRecovery(owner),
                    lease -> store.releaseRecovery(owner))
                .defaultIfEmpty(0L);
        });
    }

    public Mono<Void> provision(String eventId, int capacity) {
        return Mono.defer(() -> {
            validator.requireIdentifier(eventId);
            if (capacity < 1 || capacity > InventoryRules.MAX_EVENT_CAPACITY)
                throw new InventoryException(InventoryErrorCodes.INVALID_CAPACITY, 400);
            return store.provision(eventId, capacity);
        });
    }

    public Mono<Availability> availability(String eventId) {
        return store.availability(eventId);
    }

    public Mono<TicketPage> tickets(String eventId, int limit, String cursor) {
        if (limit < 1 || limit > InventoryRules.MAX_TICKETS_PER_PAGE)
            return Mono.error(new InventoryException(InventoryErrorCodes.INVALID_PAGE_SIZE, 400));
        return store.tickets(eventId, limit, cursor).flatMap(page -> {
            var held = page.items().stream().filter(ticket -> ticket.status().isHeld()).map(Ticket::ticketId)
                .toList();
            if (held.isEmpty())
                return Mono.just(page);
            return reclaim(eventId, held).then(Mono.defer(() -> store.tickets(eventId, limit, cursor)));
        });
    }
}
