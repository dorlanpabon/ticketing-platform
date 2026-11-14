package com.ticketing.orders.usecase;

import com.ticketing.orders.model.*;
import com.ticketing.orders.model.gateways.*;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
public final class OrdersUseCase {
    private final OrdersPort port;
    private final InventoryPort inventory;
    private final Clock clock;
    public OrdersUseCase(OrdersPort port,InventoryPort inventory,Clock clock) { this.port=port; this.inventory=inventory; this.clock=clock; }
    public Mono<Order> create(String customer,String key,String eventId,List<String> tickets,String actor,String reason,boolean complimentary) {
        return Mono.defer(() -> {
            Validation.key(key); Validation.identifier(customer); Validation.identifier(eventId); Validation.identifier(actor);
            if(tickets==null || tickets.isEmpty() || tickets.size()>10 || new HashSet<>(tickets).size()!=tickets.size()
                    || tickets.stream().anyMatch(t -> t==null || !t.matches("[1-9][0-9]{0,5}") || Integer.parseInt(t)>100000))
                return Mono.error(new DomainFailure(400,"INVALID_TICKETS"));
            if(complimentary) Validation.text(reason,500);
            var now=clock.instant();
            var sorted=tickets.stream().sorted().toList();
            String type=complimentary ? "COMPLIMENTARY" : "PURCHASE";
            Order order=new Order(UUID.randomUUID().toString(),eventId,customer,sorted,List.of(),"AVAILABLE","RESERVING","",0,now,now.plusSeconds(600),type,actor,reason==null?"":reason);
            String fingerprint=Validation.hash(eventId+"\n"+String.join(",",sorted)+"\n"+customer+"\n"+type+"\n"+order.reason());
            Envelope message=Envelope.create(complimentary ? "ComplimentaryRequested" : "PurchaseRequested",order.orderId(),payload(order),now);
            return port.create(order,Validation.hash(actor+"\n"+type+"\n"+key),fingerprint,message)
                .flatMap(saved -> inventory.reserve(saved).timeout(Duration.ofMillis(500))
                    .map(saved::withInventory).defaultIfEmpty(saved).onErrorReturn(saved));
        });
    }
    public Mono<Order> get(String customer,String id) {
        return owned(customer,id).flatMap(order -> inventory.find(id).map(order::withInventory)
                .switchIfEmpty(order.inventoryVersion()==0 ? Mono.just(order)
                        : Mono.error(new DomainFailure(503,"INVENTORY_STATE_MISSING"))));
    }
    public Mono<Order> confirm(String customer,String id,String key) {
        return Mono.defer(() -> {
            Validation.key(key);
            return get(customer,id).flatMap(order -> {
                boolean sold=order.status().equals("SOLD");
                if(!sold && !clock.instant().isBefore(order.expiresAt())) return Mono.error(new DomainFailure(410,"RESERVATION_EXPIRED"));
                if(!sold && !Set.of("RESERVED","PENDING_CONFIRMATION").contains(order.status())) return Mono.error(new DomainFailure(409,"RESERVATION_NOT_READY"));
                String commandId="confirm-"+id;
                Envelope message=new Envelope(commandId,"ConfirmPurchase",1,id,id,id,clock.instant(),Map.of("orderId",id,"customerId",customer,"commandId",commandId));
                return port.confirm(order,Validation.hash(customer+"\nconfirm\n"+key),message).thenReturn(order);
            });
        });
    }
    private Mono<Order> owned(String customer,String id) {
        return port.find(id).filter(order -> order.customerId().equals(customer)).switchIfEmpty(Mono.error(new DomainFailure(404,"ORDER_NOT_FOUND")));
    }
    public Mono<Void> receive(Envelope envelope,Reservation state) {
        if(envelope.schemaVersion()!=1 || !"ReservationStateChanged".equals(envelope.type())
                || envelope.messageId()==null || envelope.messageId().isBlank()
                || !state.orderId().equals(envelope.aggregateId())) return Mono.error(new DomainFailure(400,"UNSUPPORTED_MESSAGE"));
        return port.project(state,envelope.messageId());
    }
    public Mono<Void> reconcile() {
        return port.due().flatMap(order -> inventory.find(order.orderId())
                .switchIfEmpty(Mono.defer(() -> inventory.reserve(order)))
                .flatMap(state -> port.project(state,"reconcile-"+order.orderId()+"-"+state.inventoryVersion()))
                .then(Mono.defer(() -> port.reviewed(order)))
                .onErrorResume(error -> port.reviewFailed(order)),8).then();
    }
    public static Map<String,Object> payload(Order order) {
        return Map.of("orderId",order.orderId(),"eventId",order.eventId(),"customerId",order.customerId(),"ticketIds",order.requestedTicketIds(),
                "requestedAt",order.requestedAt().toString(),"expiresAt",order.expiresAt().toString(),"type",order.type(),"actorId",order.actorId(),"reason",order.reason());
    }
}
