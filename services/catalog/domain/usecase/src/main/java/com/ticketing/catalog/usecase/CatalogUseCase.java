package com.ticketing.catalog.usecase;

import com.ticketing.catalog.model.*;
import com.ticketing.catalog.model.gateways.CatalogPort;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
public final class CatalogUseCase {
    private final CatalogPort port;
    private final Clock clock;
    public CatalogUseCase(CatalogPort port,Clock clock) { this.port=port; this.clock=clock; }
    public Mono<Event> create(String owner,String key,String name,String venue,Instant startsAt,int capacity) {
        return Mono.defer(() -> {
            String validName=Validation.text(name,120), validVenue=Validation.text(venue,200);
            Validation.key(key);
            if(startsAt==null || !startsAt.isAfter(clock.instant()) || capacity<1 || capacity>100000)
                return Mono.error(new DomainFailure(400,"INVALID_EVENT"));
            Event event=new Event(UUID.randomUUID().toString(),validName,validVenue,startsAt,capacity,"PROVISIONING");
            String fingerprint=Validation.hash(validName+"\n"+validVenue+"\n"+startsAt+"\n"+capacity);
            Envelope message=Envelope.create("ProvisionInventory",event.eventId(),Map.of("eventId",event.eventId(),"capacity",capacity),clock.instant());
            return port.create(event,Validation.hash(owner+"\n"+key),fingerprint,message);
        });
    }
    public Mono<Event> find(String id) { return port.find(id).switchIfEmpty(Mono.error(new DomainFailure(404,"EVENT_NOT_FOUND"))); }
    public Mono<EventPage> list(int limit,String cursor) {
        if(limit<1 || limit>100) return Mono.error(new DomainFailure(400,"INVALID_PAGE_SIZE"));
        return port.list(limit,cursor);
    }
    public Mono<Void> receive(Envelope message) {
        if(message.schemaVersion()!=1 || !message.type().equals("InventoryReady")) return Mono.error(new DomainFailure(400,"UNSUPPORTED_MESSAGE"));
        Object id=message.payload().get("eventId");
        if(!(id instanceof String)) return Mono.error(new DomainFailure(400,"INVALID_MESSAGE"));
        return port.activate((String)id,message.messageId());
    }
}
