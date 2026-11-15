package com.ticketing.catalog.model.gateways;

import com.ticketing.catalog.model.*;
import reactor.core.publisher.Mono;
public interface CatalogPort {
    Mono<Event> create(Event event, String key, String fingerprint, Envelope message);
    Mono<Event> find(String eventId);
    Mono<EventPage> list(int limit,String cursor);
    Mono<Void> activate(String eventId,String messageId);
}
