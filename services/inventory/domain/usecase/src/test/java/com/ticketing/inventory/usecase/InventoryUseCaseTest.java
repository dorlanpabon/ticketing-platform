package com.ticketing.inventory.usecase;

import com.ticketing.inventory.model.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.mockito.Mockito.*;

class InventoryUseCaseTest {
    private final Instant now = Instant.parse("2026-11-16T12:00:00Z");
    private InventoryStore store;
    private InventoryUseCase service;
    private ReservationRequest request;

    @BeforeEach void setup() {
        store = mock(InventoryStore.class);
        when(store.holders(anyString(), anyList())).thenReturn(Flux.empty());
        service = new InventoryUseCase(store, Clock.fixed(now, ZoneOffset.UTC));
        request = new ReservationRequest("o1", "e1", "c1", List.of("1", "2"), now, now.plusSeconds(600), "PURCHASE", null, null);
    }

    @Test void reservesAndReplaysExistingResult() {
        var reserved = Reservation.accepted(request);
        when(store.find("o1")).thenReturn(Mono.empty());
        when(store.reserve(request)).thenReturn(Mono.just(reserved));
        StepVerifier.create(service.reserve(request)).expectNext(reserved).verifyComplete();
        when(store.find("o1")).thenReturn(Mono.just(reserved));
        StepVerifier.create(service.reserve(request)).expectNext(reserved).verifyComplete();
        verify(store, times(1)).reserve(request);
    }

    @Test void recordsUnavailableAndExpiredResultsDurably() {
        when(store.find("o1")).thenReturn(Mono.empty());
        when(store.reserve(request)).thenReturn(Mono.error(new InventoryException("TICKETS_UNAVAILABLE", 409)));
        var rejected = Reservation.rejected(request, "TICKETS_UNAVAILABLE");
        when(store.reject(request, "TICKETS_UNAVAILABLE")).thenReturn(Mono.just(rejected));
        StepVerifier.create(service.reserve(request)).expectNext(rejected).verifyComplete();
        var old = new ReservationRequest("o1", "e1", "c1", List.of("1"), now.minusSeconds(600), now, null, null, null);
        when(store.reject(old, "RESERVATION_EXPIRED")).thenReturn(Mono.just(Reservation.rejected(old, "RESERVATION_EXPIRED")));
        StepVerifier.create(service.reserve(old)).expectNextMatches(value -> value.activeTicketIds().isEmpty()).verifyComplete();
    }

    @Test void rejectsChangedPayloadAndPreservesTransientErrors() {
        when(store.find("o1")).thenReturn(Mono.just(Reservation.accepted(new ReservationRequest("o1", "e2", "c1", List.of("1"), now, now.plusSeconds(600), null, null, null))));
        expectCode(service.reserve(request), "IDEMPOTENCY_CONFLICT");
        when(store.find("o1")).thenReturn(Mono.empty());
        when(store.reserve(request)).thenReturn(Mono.error(new InventoryException("TRANSIENT", 503)));
        expectCode(service.reserve(request), "TRANSIENT");
    }

    @Test void validatesTicketsDeadlineIdentityAndCourtesy() {
        List<List<String>> invalidTickets = List.of(List.of(), List.of("1", "1"), List.of("0"), List.of("100001"), List.of("abc"),
                IntStream.rangeClosed(1, 11).mapToObj(Integer::toString).toList());
        for (var tickets : invalidTickets) expectCode(service.reserve(new ReservationRequest("o", "e", "c", tickets, now, now.plusSeconds(600), null, null, null)), "INVALID_TICKETS");
        for (String id : List.of("", "bad id", "a".repeat(201))) expectCode(service.reserve(new ReservationRequest(id, "e", "c", List.of("1"), now, now.plusSeconds(600), null, null, null)), "INVALID_IDENTIFIER");
        var deadlines = List.of(
                new ReservationRequest("o", "e", "c", List.of("1"), null, now.plusSeconds(1), null, null, null),
                new ReservationRequest("o", "e", "c", List.of("1"), now, null, null, null, null),
                new ReservationRequest("o", "e", "c", List.of("1"), now, now, null, null, null),
                new ReservationRequest("o", "e", "c", List.of("1"), now, now.plusSeconds(601), null, null, null),
                new ReservationRequest("o", "e", "c", List.of("1"), now.plusSeconds(3), now.plusSeconds(600), null, null, null));
        deadlines.forEach(value -> expectCode(service.reserve(value), "INVALID_DEADLINE"));
        expectCode(service.reserve(new ReservationRequest("o", "e", "c", List.of("1"), now, now.plusSeconds(1), "INVALID", null, null)), "INVALID_PURCHASE_TYPE");
        expectCode(service.reserve(new ReservationRequest("o", "e", "c", List.of("1"), now, now.plusSeconds(1), "COMPLIMENTARY", null, "reason")), "INVALID_COMPLIMENTARY_REASON");
        expectCode(service.reserve(new ReservationRequest("o", "e", "c", List.of("1"), now, now.plusSeconds(1), "COMPLIMENTARY", "", "reason")), "INVALID_COMPLIMENTARY_REASON");
        for (String reason : List.of("", "r".repeat(501))) expectCode(service.reserve(new ReservationRequest("o", "e", "c", List.of("1"), now, now.plusSeconds(1), "COMPLIMENTARY", "admin", reason)), "INVALID_COMPLIMENTARY_REASON");
        verifyNoInteractions(store);
    }

    @Test void confirmsInTwoStepsAndResumesPending() {
        var reserved = Reservation.accepted(request);
        var pending = reserved.transition(TicketStatus.PENDING_CONFIRMATION, "NONE", "confirm:o1");
        var sold = pending.transition(TicketStatus.SOLD, "NONE", "confirm:o1");
        when(store.find("o1")).thenReturn(Mono.just(reserved));
        when(store.transition(reserved, TicketStatus.PENDING_CONFIRMATION, "NONE", "confirm:o1", now)).thenReturn(Mono.just(pending));
        when(store.transition(pending, TicketStatus.SOLD, "NONE", "confirm:o1", now)).thenReturn(Mono.just(sold));
        StepVerifier.create(service.confirm("o1", "c1", "confirm:o1")).expectNext(sold).verifyComplete();
        when(store.find("o1")).thenReturn(Mono.just(pending));
        StepVerifier.create(service.confirm("o1", "c1", "confirm:o1")).expectNext(sold).verifyComplete();
        expectCode(service.confirm("o1", "c1", "other"), "CONFIRMATION_CONFLICT");
        when(store.find("o1")).thenReturn(Mono.just(sold));
        StepVerifier.create(service.confirm("o1", "c1", "confirm:o1")).expectNext(sold).verifyComplete();
        expectCode(service.confirm("o1", "other", "confirm:o1"), "RESERVATION_NOT_FOUND");
    }

    @Test void cannotConfirmExpiredRejectedOrAbsentReservations() {
        var oldRequest = new ReservationRequest("o1", "e1", "c1", List.of("1"), now.minusSeconds(600), now, null, null, null);
        var reserved = Reservation.accepted(oldRequest);
        var expired = reserved.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null);
        when(store.find("o1")).thenReturn(Mono.just(reserved));
        when(store.transition(reserved, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now)).thenReturn(Mono.just(expired));
        StepVerifier.create(service.confirm("o1", "c1", "cmd")).expectNext(expired).verifyComplete();
        when(store.find("o1")).thenReturn(Mono.just(expired));
        expectCode(service.confirm("o1", "c1", "cmd"), "RESERVATION_EXPIRED");
        when(store.find("o1")).thenReturn(Mono.just(Reservation.rejected(request, "TICKETS_UNAVAILABLE")));
        expectCode(service.confirm("o1", "c1", "cmd"), "INVALID_TRANSITION");
        when(store.find("o1")).thenReturn(Mono.empty());
        expectCode(service.get("o1"), "RESERVATION_NOT_FOUND");
        expectCode(service.confirm("o1", "c1", null), "INVALID_IDENTIFIER");
    }

    @Test void resolvesRacesWithoutOverridingFinalState() {
        var reserved = Reservation.accepted(request);
        var expired = reserved.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null);
        when(store.find("o1")).thenReturn(Mono.just(reserved), Mono.just(expired));
        when(store.transition(reserved, TicketStatus.PENDING_CONFIRMATION, "NONE", "cmd", now))
                .thenReturn(Mono.error(new InventoryException("CONCURRENT_TRANSITION", 409)));
        StepVerifier.create(service.confirm("o1", "c1", "cmd")).expectNext(expired).verifyComplete();
        when(store.find("o1")).thenReturn(Mono.just(reserved));
        expectCode(service.confirm("o1", "c1", "cmd"), "CONCURRENT_TRANSITION");
    }

    @Test void expiresWholeReservationsAndIgnoresOnlyKnownRaces() {
        var reserved = Reservation.accepted(request);
        when(store.expired(now, 10)).thenReturn(Flux.just(reserved));
        when(store.transition(reserved, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now))
                .thenReturn(Mono.just(reserved.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null)));
        StepVerifier.create(service.expireDue(10)).expectNext(1L).verifyComplete();
        when(store.transition(reserved, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now))
                .thenReturn(Mono.error(new InventoryException("CONCURRENT_TRANSITION", 409)));
        StepVerifier.create(service.expireDue(10)).expectNext(0L).verifyComplete();
        when(store.transition(reserved, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now))
                .thenReturn(Mono.error(new InventoryException("TRANSIENT", 503)));
        expectCode(service.expireDue(10), "TRANSIENT");
    }

    @Test void delegatesProvisionAndReadModelsWithinLimits() {
        when(store.provision("e1", 100)).thenReturn(Mono.empty());
        StepVerifier.create(service.provision("e1", 100)).verifyComplete();
        expectCode(service.provision("e1", 0), "INVALID_CAPACITY");
        expectCode(service.provision("e1", 100001), "INVALID_CAPACITY");
        expectCode(service.tickets("e1", 0, null), "INVALID_PAGE_SIZE");
        expectCode(service.tickets("e1", 101, null), "INVALID_PAGE_SIZE");
        var availability = new Availability("e1", 100, 100, 0, 0, 0, 0);
        when(store.availability("e1")).thenReturn(Mono.just(availability));
        StepVerifier.create(service.availability("e1")).expectNext(availability).verifyComplete();
        var page = new TicketPage(List.of(), null);
        when(store.tickets("e1", 50, null)).thenReturn(Mono.just(page));
        StepVerifier.create(service.tickets("e1", 50, null)).expectNext(page).verifyComplete();
    }

    @Test void authoritativeReadExpiresAtBoundaryAndRetriesCompetingReplica() {
        var old = Reservation.accepted(new ReservationRequest("o1", "e1", "c1", List.of("1", "2"),
                now.minusSeconds(600), now, null, null, null));
        var expired = old.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null);
        when(store.find("o1")).thenReturn(Mono.just(old), Mono.just(expired));
        when(store.transition(old, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now))
                .thenReturn(Mono.error(new InventoryException("CONCURRENT_TRANSITION", 409)));
        StepVerifier.create(service.get("o1")).expectNext(expired).verifyComplete();
        when(store.find("o1")).thenReturn(Mono.just(old));
        expectCode(service.get("o1"), "CONCURRENT_TRANSITION");
        when(store.transition(old, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now))
                .thenReturn(Mono.error(new InventoryException("UNAVAILABLE", 503)));
        expectCode(service.get("o1"), "UNAVAILABLE");
        when(store.find("o1")).thenReturn(Mono.error(new IllegalStateException("storage")));
        StepVerifier.create(service.get("o1")).expectError(IllegalStateException.class).verify();
    }

    @Test void reclaimsWholeExpiredOwnerBeforeReservingOrReadingSelectedTickets() {
        var old = Reservation.accepted(new ReservationRequest("old", "e1", "c1", List.of("1", "2"),
                now.minusSeconds(600), now, null, null, null));
        var expired = old.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null);
        when(store.find("o1")).thenReturn(Mono.empty());
        when(store.holders("e1", List.of("1", "2"))).thenReturn(Flux.just(old));
        when(store.find("old")).thenReturn(Mono.just(old));
        when(store.transition(old, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now)).thenReturn(Mono.just(expired));
        when(store.reserve(request)).thenReturn(Mono.just(Reservation.accepted(request)));
        StepVerifier.create(service.reserve(request)).expectNextMatches(value -> value.status() == TicketStatus.RESERVED).verifyComplete();
        var order = inOrder(store);
        order.verify(store).transition(old, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now);
        order.verify(store).reserve(request);
        var heldPage = new TicketPage(List.of(new Ticket("e1", "1", TicketStatus.RESERVED), new Ticket("e1", "2", TicketStatus.RESERVED)), null);
        var freePage = new TicketPage(List.of(new Ticket("e1", "1", TicketStatus.AVAILABLE), new Ticket("e1", "2", TicketStatus.AVAILABLE)), null);
        when(store.tickets("e1", 2, null)).thenReturn(Mono.just(heldPage), Mono.just(freePage));
        StepVerifier.create(service.tickets("e1", 2, null)).expectNext(freePage).verifyComplete();
    }

    @Test void deadlineIsRecheckedAfterReclaimWithoutCreatingLateHold() {
        var movingClock = mock(Clock.class);
        when(movingClock.instant()).thenReturn(now);
        var timed = new InventoryUseCase(store, movingClock);
        when(store.find("o1")).thenReturn(Mono.empty());
        when(store.holders(anyString(), anyList())).thenReturn(Flux.defer(() -> {
            when(movingClock.instant()).thenReturn(request.expiresAt());
            return Flux.empty();
        }));
        var expired = Reservation.rejected(request, "RESERVATION_EXPIRED");
        when(store.reject(request, "RESERVATION_EXPIRED")).thenReturn(Mono.just(expired));
        StepVerifier.create(timed.reserve(request)).expectNext(expired).verifyComplete();
        verify(store, never()).reserve(any());
    }

    @Test void recordsExpiryDetectedByAdapterAndReportsMaximumObservedLagBeforeRelease() {
        when(store.find("o1")).thenReturn(Mono.empty());
        when(store.reserve(request)).thenReturn(Mono.error(new InventoryException("RESERVATION_EXPIRED", 409)));
        var rejected = Reservation.rejected(request, "RESERVATION_EXPIRED");
        when(store.reject(request, "RESERVATION_EXPIRED")).thenReturn(Mono.just(rejected));
        StepVerifier.create(service.reserve(request)).expectNext(rejected).verifyComplete();
        var observed = new java.util.concurrent.atomic.AtomicLong(-1);
        var first = Reservation.accepted(new ReservationRequest("old1", "e1", "c1", List.of("1"), now.minusSeconds(607), now.minusSeconds(7), null, null, null));
        var second = Reservation.accepted(new ReservationRequest("old2", "e1", "c1", List.of("2"), now.minusSeconds(603), now.minusSeconds(3), null, null, null));
        when(store.expired(now, 10)).thenReturn(Flux.just(first, second));
        when(store.transition(any(), eq(TicketStatus.AVAILABLE), eq("RESERVATION_EXPIRED"), isNull(), eq(now))).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertEquals(7, observed.get());
            Reservation value = call.getArgument(0);
            return Mono.just(value.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null));
        });
        StepVerifier.create(service.expireDue(10, observed::set)).expectNext(2L).verifyComplete();
        when(store.expired(now, 10)).thenReturn(Flux.empty());
        StepVerifier.create(service.expireDue(10, observed::set)).expectNext(0L).verifyComplete();
        org.junit.jupiter.api.Assertions.assertEquals(0, observed.get());
    }

    private void expectCode(Mono<?> result, String code) {
        StepVerifier.create(result).expectErrorMatches(error -> error instanceof InventoryException inventory && code.equals(inventory.code())).verify();
    }

    @Test void expiryTimerChecksOriginalDeadlineAndNeverReleasesFinalReservations() {
        var reserved = Reservation.accepted(request);
        when(store.find("o1")).thenReturn(Mono.just(reserved));
        expectCode(service.expireReservation("o1", request.expiresAt()), "RESERVATION_NOT_DUE");
        expectCode(service.expireReservation("o1", now), "EXPIRY_DEADLINE_MISMATCH");
        var sold = reserved.transition(TicketStatus.PENDING_CONFIRMATION, "NONE", "cmd").transition(TicketStatus.SOLD, "NONE", "cmd");
        when(store.find("o1")).thenReturn(Mono.just(sold));
        StepVerifier.create(service.expireReservation("o1", request.expiresAt())).expectNext(sold).verifyComplete();
        var expired = reserved.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null);
        when(store.find("o1")).thenReturn(Mono.just(expired));
        StepVerifier.create(service.expireReservation("o1", request.expiresAt())).expectNext(expired).verifyComplete();
        when(store.find("o1")).thenReturn(Mono.empty());
        expectCode(service.expireReservation("o1", request.expiresAt()), "RESERVATION_NOT_FOUND");
        verify(store, never()).transition(any(), any(), anyString(), any(), any());
    }

    @Test void recoveryClaimsBeforeQueryingAndProcessesAllBatchesWithFencedCleanup() {
        when(store.claimRecovery(anyString(), eq(now))).thenReturn(Mono.just(new WorkLease(false, now.plusSeconds(60))));
        StepVerifier.create(service.recoverExpired(100, ignored -> { })).expectNext(0L).verifyComplete();
        verify(store, never()).expired(any(), anyInt());
        verify(store, never()).releaseRecovery(anyString());
        when(store.claimRecovery(anyString(), eq(now))).thenReturn(Mono.just(new WorkLease(true, now.plusSeconds(55))));
        when(store.releaseRecovery(anyString())).thenReturn(Mono.empty());
        when(store.expired(now, 100)).thenReturn(Flux.range(0, 250).map(index -> Reservation.accepted(new ReservationRequest(
                "old-" + index, "e1", "c1", List.of("1"), now.minusSeconds(600), now, null, null, null))));
        when(store.transition(any(), eq(TicketStatus.AVAILABLE), eq("RESERVATION_EXPIRED"), isNull(), eq(now)))
                .thenAnswer(call -> Mono.just(((Reservation) call.getArgument(0)).transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null)));
        StepVerifier.create(service.recoverExpired(100, ignored -> { })).expectNext(250L).verifyComplete();
        verify(store, times(250)).transition(any(), any(), anyString(), isNull(), any());
        verify(store).releaseRecovery(anyString());
        when(store.expired(now, 100)).thenReturn(Flux.error(new IllegalStateException("storage")));
        StepVerifier.create(service.recoverExpired(100, ignored -> { })).expectError(IllegalStateException.class).verify();
        verify(store, times(2)).releaseRecovery(anyString());
        when(store.expired(now, 100)).thenReturn(Flux.never());
        var active = service.recoverExpired(100, ignored -> { }).subscribe();
        active.dispose();
        verify(store, times(3)).releaseRecovery(anyString());
        StepVerifier.withVirtualTime(() -> service.recoverExpired(100, ignored -> { }))
                .thenAwait(java.time.Duration.ofSeconds(46)).expectError(java.util.concurrent.TimeoutException.class).verify();
        verify(store, times(4)).releaseRecovery(anyString());
    }

    @Test void timerExpiresAtExactDeadlineAndReportsOnlyActualDueHolds() {
        var due = Reservation.accepted(new ReservationRequest("o1", "e1", "c1", List.of("1"), now.minusSeconds(600), now, null, null, null));
        var expired = due.transition(TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null);
        when(store.find("o1")).thenReturn(Mono.just(due));
        when(store.transition(due, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, now)).thenReturn(Mono.just(expired));
        var lag = new java.util.concurrent.atomic.AtomicLong(-1);
        StepVerifier.create(service.expireReservation("o1", now, lag::set)).expectNext(expired).verifyComplete();
        org.junit.jupiter.api.Assertions.assertEquals(0, lag.get());
    }
}
