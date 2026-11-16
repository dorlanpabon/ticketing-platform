package com.ticketing.orders.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketing.orders.model.DomainFailure;
import com.ticketing.orders.model.Envelope;
import com.ticketing.orders.model.Order;
import com.ticketing.orders.model.OrderSubmission;
import com.ticketing.orders.model.Reservation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.reactivecommons.utils.ObjectMapperImp;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@Tag("integration")
class PersistenceIntegrationTest {
    private static DynamoDbAsyncClient client;
    private static String table;
    private final Instant now = Instant.parse("2026-11-16T12:00:00Z");
    private OrderRepository adapter;

    @BeforeAll
    static void createLocalTable() {
        URI endpoint = URI
            .create(System.getenv().getOrDefault("DYNAMODB_ENDPOINT", "http://127.0.0.1:18000"));
        if (!Set.of("localhost", "127.0.0.1", "dynamodb").contains(endpoint.getHost()))
            throw new IllegalArgumentException("Local endpoint required");
        client = DynamoDbAsyncClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("localtest", "localtest")))
            .build();
        table = "orders-integration-" + UUID.randomUUID();
        var attributes = List.of("pk", "sk", "gsi1pk", "gsi1sk").stream().map(name -> AttributeDefinition
            .builder().attributeName(name).attributeType(ScalarAttributeType.S).build()).toList();
        Mono.fromFuture(
            () -> client.createTable(
                CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(attributes).keySchema(keys("pk", "sk"))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("gsi1")
                        .keySchema(keys("gsi1pk", "gsi1sk"))
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build())
                    .build()))
            .block(Duration.ofSeconds(20));
    }

    @AfterAll
    static void cleanOwnTable() {
        if (client != null) {
            if (table != null)
                Mono.fromFuture(
                    () -> client.deleteTable(DeleteTableRequest.builder().tableName(table).build()))
                    .block(Duration.ofSeconds(20));
            client.close();
        }
    }

    @BeforeEach
    void setup() {
        adapter = new OrderRepository(client, table, Clock.fixed(now, ZoneOffset.UTC), new ObjectMapperImp());
    }

    @Test
    void concurrentIdempotentCreationsProduceExactlyOneOrderAndCommand() {
        String key = UUID.randomUUID().toString();
        var attempts = IntStream.range(0, 20).mapToObj(index -> order(UUID.randomUUID().toString())).toList();
        var results = Flux.fromIterable(attempts)
            .flatMap(order -> adapter.create(order, key, "same-request", purchase(order)), 20).collectList()
            .block(Duration.ofSeconds(30));
        assertNotNull(results);
        assertEquals(20, results.size());
        assertEquals(1, results.stream().map(submission -> submission.order().orderId()).distinct().count());
        assertEquals(1, results.stream().map(OrderSubmission::messageId).distinct().count());
        var persisted = Flux.fromIterable(attempts).flatMap(order -> adapter.find(order.orderId()), 8)
            .collectList().block(Duration.ofSeconds(10));
        assertNotNull(persisted);
        assertEquals(1, persisted.size());
        var winner = results.getFirst();
        assertEquals(winner.messageId(), get("IDEMP#" + key).get("purchaseMessageId").s());
        assertFalse(get("OUTBOX#" + winner.messageId()).isEmpty());
        assertThrows(DomainFailure.class,
            () -> adapter.create(order("different"), key, "different-request", purchase(order("different")))
                .block(Duration.ofSeconds(10)));
    }

    @Test
    void concurrentLegacyReplaysBackfillOneOriginalIntentAndRetainTheIdempotencyExpiry() {
        String key = UUID.randomUUID().toString();
        var original = order(UUID.randomUUID().toString());
        String originalTtl = Long.toString(now.plus(Duration.ofDays(5)).getEpochSecond());
        put(adapter.item("ORDER#" + original.orderId(), original));
        put(Map.of("pk", AttributeValue.fromS("IDEMP#" + key), "sk", AttributeValue.fromS("META"), "entityId",
            AttributeValue.fromS(original.orderId()), "fingerprint", AttributeValue.fromS("hash"), "ttl",
            AttributeValue.fromN(originalTtl)));

        var attempt = order(UUID.randomUUID().toString());
        var other = order(UUID.randomUUID().toString());
        var replays = Flux
            .merge(adapter.create(attempt, key, "hash", purchase(attempt)),
                adapter.create(other, key, "hash", purchase(other)))
            .collectList().block(Duration.ofSeconds(20));

        assertNotNull(replays);
        assertEquals(2, replays.size());
        assertEquals(replays.getFirst(), replays.getLast());
        var restored = replays.getFirst();
        assertEquals(original, restored.order());
        assertEquals(originalTtl, get("IDEMP#" + key).get("ttl").n());
        var claimed = adapter.claim(restored.messageId()).block(Duration.ofSeconds(10));
        assertNotNull(claimed);
        assertEquals(original.orderId(), claimed.envelope().aggregateId());
        assertEquals(original.requestedAt(), claimed.envelope().occurredAt());
        assertEquals(original.expiresAt().toString(), claimed.envelope().payload().get("expiresAt"));
        assertEquals(original.requestedTicketIds(), claimed.envelope().payload().get("ticketIds"));
        adapter.acknowledge(claimed).block(Duration.ofSeconds(10));
        assertEquals(restored,
            adapter.create(other, key, "hash", purchase(other)).block(Duration.ofSeconds(10)));
        assertFalse(adapter.pending(restored.messageId()).block(Duration.ofSeconds(10)));
    }

    @Test
    void releasingADirectClaimAllowsRetryWhileAStaleOwnerCannotRemoveTheMessage() {
        var order = order(UUID.randomUUID().toString());
        var message = purchase(order);
        adapter.create(order, UUID.randomUUID().toString(), "hash", message).block(Duration.ofSeconds(10));
        var first = adapter.claim(message.messageId()).block(Duration.ofSeconds(10));
        assertNotNull(first);
        assertTrue(adapter.claim(message.messageId()).blockOptional(Duration.ofSeconds(10)).isEmpty());
        assertTrue(adapter.pending(message.messageId()).block(Duration.ofSeconds(10)));
        adapter.release(first).block(Duration.ofSeconds(10));
        var retry = adapter.claim(message.messageId()).block(Duration.ofSeconds(10));
        assertNotNull(retry);
        assertEquals(first.envelope(), retry.envelope());
        adapter.acknowledge(first).block(Duration.ofSeconds(10));
        assertTrue(adapter.pending(message.messageId()).block(Duration.ofSeconds(10)));
        adapter.acknowledge(retry).block(Duration.ofSeconds(10));
        assertFalse(adapter.pending(message.messageId()).block(Duration.ofSeconds(10)));
    }

    @Test
    void racingConfirmationKeysAreBothBoundAndEmitOnlyOneCommand() {
        var first = create();
        var second = create();
        String key1 = UUID.randomUUID().toString(), key2 = UUID.randomUUID().toString();
        var message = confirmation(first);
        Flux.merge(adapter.confirm(first, key1, message), adapter.confirm(first, key2, message)).then()
            .block(Duration.ofSeconds(20));
        assertThrows(DomainFailure.class,
            () -> adapter.confirm(second, key1, confirmation(second)).block(Duration.ofSeconds(10)));
        assertThrows(DomainFailure.class,
            () -> adapter.confirm(second, key2, confirmation(second)).block(Duration.ofSeconds(10)));
        var command = get("COMMAND#" + message.messageId());
        var outbox = get("OUTBOX#" + message.messageId());
        assertFalse(command.isEmpty());
        assertFalse(outbox.isEmpty());
        adapter.confirm(first, key1, message).block(Duration.ofSeconds(10));
    }

    @Test
    void projectionIgnoresOldEventsAndRetainsFinalState() {
        var order = create();
        var sold = new Reservation(order.orderId(), order.eventId(), order.customerId(), "SOLD", "NONE", 3,
            order.requestedTicketIds(), order.requestedTicketIds(), order.expiresAt());
        adapter.project(sold, "sold-" + order.orderId()).block(Duration.ofSeconds(10));
        adapter.project(sold, "sold-" + order.orderId()).block(Duration.ofSeconds(10));
        var old = new Reservation(order.orderId(), order.eventId(), order.customerId(), "RESERVED", "NONE", 1,
            order.requestedTicketIds(), order.requestedTicketIds(), order.expiresAt());
        adapter.project(old, "old-" + order.orderId()).block(Duration.ofSeconds(10));
        var persisted = adapter.find(order.orderId()).block(Duration.ofSeconds(10));
        assertNotNull(persisted);
        assertEquals("SOLD", persisted.status());
        assertEquals(3, persisted.inventoryVersion());
        assertFalse(get("ORDER#" + order.orderId()).containsKey("gsi1pk"));
    }

    @Test
    void outboxLeaseIsExclusiveAndAcknowledgementRemovesOnlyOwnedRecord() {
        create();
        var first = adapter.claim().collectList().block(Duration.ofSeconds(10));
        assertNotNull(first);
        assertFalse(first.isEmpty());
        var second = adapter.claim().collectList().block(Duration.ofSeconds(10));
        assertNotNull(second);
        assertTrue(second.isEmpty());
        for (var message : first)
            adapter.acknowledge(message).block(Duration.ofSeconds(10));
        for (var message : first)
            assertTrue(get(message.key()).isEmpty());
    }

    private Order create() {
        var order = order(UUID.randomUUID().toString());
        return adapter.create(order, UUID.randomUUID().toString(), "request", purchase(order))
            .map(OrderSubmission::order).block(Duration.ofSeconds(10));
    }

    private Order order(String id) {
        return new Order(id, "event", "customer", List.of("1"), List.of(), "AVAILABLE", "RESERVING", "", 0,
            now, now.plusSeconds(600), "PURCHASE", "customer", "");
    }

    private Envelope purchase(Order order) {
        return Envelope.create("PurchaseRequested", order.orderId(), Map.of("orderId", order.orderId()), now);
    }

    private Envelope confirmation(Order order) {
        return new Envelope("confirm-" + order.orderId(), "ConfirmPurchase", 1, order.orderId(),
            order.orderId(), order.orderId(), now, Map.of("orderId", order.orderId(), "customerId",
                order.customerId(), "commandId", "confirm-" + order.orderId()));
    }

    private Map<String, AttributeValue> get(String key) {
        return Mono
            .fromFuture(() -> client.getItem(GetItemRequest.builder().tableName(table).consistentRead(true)
                .key(Map.of("pk", AttributeValue.fromS(key), "sk", AttributeValue.fromS("META"))).build()))
            .block(Duration.ofSeconds(10)).item();
    }

    private void put(Map<String, AttributeValue> item) {
        Mono.fromFuture(() -> client.putItem(PutItemRequest.builder().tableName(table).item(item).build()))
            .block(Duration.ofSeconds(10));
    }

    private static List<KeySchemaElement> keys(String pk, String sk) {
        return List.of(KeySchemaElement.builder().attributeName(pk).keyType(KeyType.HASH).build(),
            KeySchemaElement.builder().attributeName(sk).keyType(KeyType.RANGE).build());
    }
}
