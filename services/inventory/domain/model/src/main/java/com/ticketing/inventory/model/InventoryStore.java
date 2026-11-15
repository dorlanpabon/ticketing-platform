package com.ticketing.inventory.model;

import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InventoryStore {
    Mono<Reservation> find(String orderId);
    Flux<Reservation> holders(String eventId, java.util.List<String> ticketIds);
    Mono<Reservation> reserve(ReservationRequest request);
    Mono<Reservation> reject(ReservationRequest request, String reason);
    Mono<Reservation> transition(Reservation expected, TicketStatus target, String resolution,
                                 String commandId, Instant observedAt);
    Mono<Void> provision(String eventId, int capacity);
    Mono<Availability> availability(String eventId);
    Mono<TicketPage> tickets(String eventId, int limit, String cursor);
    Flux<Reservation> expired(Instant now, int limit);
    Mono<WorkLease> claimRecovery(String owner, Instant now);
    Mono<Void> releaseRecovery(String owner);
}
