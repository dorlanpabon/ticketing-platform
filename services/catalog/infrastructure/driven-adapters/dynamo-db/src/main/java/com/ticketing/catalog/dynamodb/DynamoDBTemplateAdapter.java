package com.ticketing.catalog.dynamodb;

import com.ticketing.catalog.model.*;
import com.ticketing.catalog.model.gateways.CatalogPort;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.*;
@Repository
public final class DynamoDBTemplateAdapter extends JsonTable implements CatalogPort {
    public DynamoDBTemplateAdapter(DynamoDbAsyncClient client,@Value("${DYNAMODB_TABLE_NAME:ticketing-local-catalog}") String table,Clock clock,org.reactivecommons.utils.ObjectMapper mapper) { super(client,table,clock,mapper); }
    @Override public Mono<Event> create(Event event,String key,String fingerprint,Envelope message) {
        return previous(key,fingerprint).flatMap(this::find).switchIfEmpty(Mono.defer(() ->
                transact(List.of(put(item("EVENT#"+event.eventId(),event),"attribute_not_exists(pk)"),idempotency(key,fingerprint,event.eventId()),outbox(message)))
                        .thenReturn(event).onErrorResume(TransactionCanceledException.class,error -> previous(key,fingerprint).flatMap(this::find).switchIfEmpty(Mono.error(error)))));
    }
    @Override public Mono<Event> find(String eventId) { return get("EVENT#"+eventId).map(row -> decode(row,Event.class)); }
    @Override public Mono<EventPage> list(int limit,String cursor) {
        return Mono.defer(() -> {
            QueryRequest.Builder query=QueryRequest.builder().tableName(table).indexName("gsi2")
                    .keyConditionExpression("gsi2pk = :events").expressionAttributeValues(Map.of(":events",text("ACTIVE_EVENTS"))).limit(limit);
            if(cursor!=null && !cursor.isBlank()) {
                try {
                    String[] values=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\n",-1);
                    if(values.length!=2 || values[0].length()>160 || values[1].length()>200) throw new IllegalArgumentException("Invalid cursor");
                    query.exclusiveStartKey(Map.of("pk",text(values[0]),"sk",text("META"),"gsi2pk",text("ACTIVE_EVENTS"),"gsi2sk",text(values[1])));
                } catch(IllegalArgumentException error) { return Mono.error(new DomainFailure(400,"INVALID_CURSOR")); }
            }
            return Mono.fromFuture(() -> client.query(query.build())).map(result -> {
                String next=result.hasLastEvaluatedKey() ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                        (result.lastEvaluatedKey().get("pk").s()+"\n"+result.lastEvaluatedKey().get("gsi2sk").s()).getBytes(StandardCharsets.UTF_8)) : null;
                return new EventPage(result.items().stream().map(row -> decode(row,Event.class)).toList(),next);
            });
        });
    }
    @Override public Mono<Void> activate(String eventId,String messageId) {
        return find(eventId).switchIfEmpty(Mono.error(new DomainFailure(404,"UNKNOWN_EVENT_MESSAGE"))).flatMap(event -> {
            if(event.status().equals("ACTIVE")) return Mono.empty();
            var row=item("EVENT#"+eventId,event.activate()); row.put("gsi2pk",text("ACTIVE_EVENTS")); row.put("gsi2sk",text(event.startsAt()+"#"+eventId));
            return transact(List.of(put(row,"attribute_exists(pk)"),inbox(messageId)))
                    .onErrorResume(TransactionCanceledException.class,error -> find(eventId).filter(current -> current.status().equals("ACTIVE")).switchIfEmpty(Mono.error(error)).then());
        });
    }
}
