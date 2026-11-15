package com.ticketing.orders.consumer;
import com.ticketing.orders.model.Reservation;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
@JsonIgnoreProperties(ignoreUnknown=true)
public record ObjectResponse(String orderId,String eventId,String customerId,String status,String resolution,
                             long inventoryVersion,List<String> requestedTicketIds,List<String> activeTicketIds,Instant expiresAt) {
    public Reservation toReservation() {
        return new Reservation(orderId,eventId,customerId,status,resolution,inventoryVersion,requestedTicketIds,activeTicketIds,expiresAt);
    }
}
