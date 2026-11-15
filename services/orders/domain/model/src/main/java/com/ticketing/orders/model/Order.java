package com.ticketing.orders.model;

import java.time.Instant;
import java.util.List;
public record Order(String orderId,String eventId,String customerId,List<String> requestedTicketIds,
                    List<String> activeTicketIds,String status,String workflowStatus,String resolution,
                    long inventoryVersion,Instant requestedAt,Instant expiresAt,String type,String actorId,String reason) {
    public Order { requestedTicketIds=List.copyOf(requestedTicketIds); activeTicketIds=List.copyOf(activeTicketIds); }
    public Order withInventory(Reservation state) {
        if(!matchesInventoryIdentity(state))
            throw new DomainFailure(409,"INVENTORY_IDENTITY_MISMATCH");
        if(state.inventoryVersion()<=inventoryVersion) return this;
        if(isFinal() && !status.equals(state.status()))
            throw new DomainFailure(409,"INVALID_INVENTORY_TRANSITION");
        return new Order(orderId,eventId,customerId,requestedTicketIds,state.activeTicketIds(),state.status(),workflowFor(state),
                state.resolution(),state.inventoryVersion(),requestedAt,expiresAt,type,actorId,reason);
    }
    private boolean matchesInventoryIdentity(Reservation state) {
        return orderId.equals(state.orderId()) && eventId.equals(state.eventId()) && customerId.equals(state.customerId())
                && requestedTicketIds.equals(state.requestedTicketIds()) && expiresAt.equals(state.expiresAt());
    }
    private boolean isFinal() { return status.equals("SOLD") || status.equals("COMPLIMENTARY"); }
    private static String workflowFor(Reservation state) {
        return switch(state.status()) {
            case "RESERVED" -> "AWAITING_CONFIRMATION";
            case "PENDING_CONFIRMATION" -> "CONFIRMING";
            case "SOLD","COMPLIMENTARY" -> "COMPLETED";
            case "AVAILABLE" -> "RESERVATION_EXPIRED".equals(state.resolution()) || "EXPIRED".equals(state.resolution()) ? "EXPIRED" : "REJECTED";
            default -> throw new DomainFailure(409,"INVALID_INVENTORY_STATE");
        };
    }
}
