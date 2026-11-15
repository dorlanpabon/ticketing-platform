package com.ticketing.orders.consumer;
import com.ticketing.orders.model.Order;
import java.time.Instant;
import java.util.List;
public record ObjectRequest(String eventId,String customerId,List<String> ticketIds,Instant requestedAt,
                            Instant expiresAt,String type,String actorId,String reason) {
    public static ObjectRequest from(Order order) {
        return new ObjectRequest(order.eventId(),order.customerId(),order.requestedTicketIds(),order.requestedAt(),
                order.expiresAt(),order.type(),order.actorId(),order.reason());
    }
}
