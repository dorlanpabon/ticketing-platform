package com.ticketing.inventory.dynamodb;

import com.ticketing.inventory.model.*;
import com.ticketing.inventory.usecase.InventoryUseCase;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class DynamoInventoryIntegrationTest {
    private static DynamoDbAsyncClient client;
    private static String table;
    private MutableClock clock;
    private DynamoDBTemplateAdapter store;
    private InventoryUseCase inventory;

    @BeforeAll static void createLocalTable() {
        URI endpoint = URI.create(System.getenv().getOrDefault("DYNAMODB_ENDPOINT", "http://127.0.0.1:18000"));
        if (!Set.of("localhost", "127.0.0.1", "dynamodb").contains(endpoint.getHost())) {
            throw new IllegalArgumentException("Integration tests require a local DynamoDB endpoint");
        }
        client = DynamoDbAsyncClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("localtest", "localtest"))).build();
        table = "inventory-integration-" + UUID.randomUUID();
        var attributes = List.of("pk", "sk", "gsi1pk", "gsi1sk", "gsi2pk", "gsi2sk").stream()
                .map(name -> AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build()).toList();
        Mono.fromFuture(() -> client.createTable(CreateTableRequest.builder().tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attributes).keySchema(keys("pk", "sk"))
                .globalSecondaryIndexes(index("gsi1"), index("gsi2")).build())).block(Duration.ofSeconds(20));
    }

    @AfterAll static void deleteOnlyOwnTable() {
        if (client != null) {
            if (table != null) Mono.fromFuture(() -> client.deleteTable(DeleteTableRequest.builder().tableName(table).build())).block(Duration.ofSeconds(20));
            client.close();
        }
    }

    @BeforeEach void setup() {
        clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
        store = new DynamoDBTemplateAdapter(client, table, clock);
        inventory = new InventoryUseCase(store, clock);
    }

    @Test void realConditionalTransactionsNeverDoubleReserveOneSeat() {
        String event = event(1);
        var results = Flux.range(0, 100).flatMap(index -> inventory.reserve(request("race-" + event + "-" + index, event, List.of("1")))
                        .map(reservation -> reservation.status() == TicketStatus.RESERVED)
                        .onErrorResume(DynamoDbException.class, error -> Mono.just(false)), 100)
                .collectList().block(Duration.ofSeconds(60));
        assertNotNull(results);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        var availability = store.availability(event).block(Duration.ofSeconds(10));
        assertNotNull(availability);
        assertEquals(0, availability.available());
        assertEquals(1, availability.reserved());
        assertEquals(1, availability.capacity());
    }

    @Test void conflictingMultiSeatReservationMakesNoPartialChanges() {
        String event = event(2);
        inventory.reserve(request("owner-" + event, event, List.of("2"))).block(Duration.ofSeconds(10));
        var rejected = inventory.reserve(request("loser-" + event, event, List.of("1", "2"))).block(Duration.ofSeconds(10));
        assertNotNull(rejected); assertEquals("TICKETS_UNAVAILABLE", rejected.resolution());
        assertTrue(rejected.activeTicketIds().isEmpty());
        var tickets = store.tickets(event, 2, null).block(Duration.ofSeconds(10));
        assertNotNull(tickets);
        assertEquals(TicketStatus.AVAILABLE, tickets.items().getFirst().status());
        assertEquals(TicketStatus.RESERVED, tickets.items().getLast().status());
        assertEquals(rejected, inventory.reserve(request("loser-" + event, event, List.of("1", "2"))).block(Duration.ofSeconds(10)));
    }

    @Test void confirmReplayIsFinalAndOldExpiryCannotReleaseSoldSeats() {
        String event = event(2);
        var request = request("confirm-" + event, event, List.of("1", "2"));
        var original = inventory.reserve(request).block(Duration.ofSeconds(10));
        var sold = inventory.confirm(request.orderId(), "customer", "confirm:" + request.orderId()).block(Duration.ofSeconds(10));
        assertNotNull(sold); assertEquals(TicketStatus.SOLD, sold.status());
        assertEquals(sold, inventory.confirm(request.orderId(), "customer", "confirm:" + request.orderId()).block(Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(600));
        assertThrows(InventoryException.class, () -> store.transition(original, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, clock.instant()).block(Duration.ofSeconds(10)));
        var available = store.availability(event).block(Duration.ofSeconds(10));
        assertNotNull(available); assertEquals(2, available.sold()); assertEquals(0, available.available());
    }

    @Test void expiresWholeHoldAndAnOldCommandCannotTouchItsNewOwner() {
        String event = event(2);
        var request = request("expired-" + event, event, List.of("1", "2"));
        var old = inventory.reserve(request).block(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(600));
        var expired = inventory.confirm(request.orderId(), "customer", "confirm:" + request.orderId()).block(Duration.ofSeconds(10));
        assertNotNull(expired); assertEquals(TicketStatus.AVAILABLE, expired.status());
        var newRequest = request("new-" + event, event, List.of("1", "2"));
        var next = inventory.reserve(newRequest).block(Duration.ofSeconds(10));
        assertNotNull(next); assertEquals(TicketStatus.RESERVED, next.status());
        assertEquals(expired, inventory.reserve(request).block(Duration.ofSeconds(10)));
        assertThrows(InventoryException.class, () -> store.transition(old, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, clock.instant()).block(Duration.ofSeconds(10)));
        assertEquals(next, inventory.get(newRequest.orderId()).block(Duration.ofSeconds(10)));
    }

    @Test void resumesProvisioningWithoutDuplicatingSeatsAndKeepsCourtesyNonAccounting() {
        String event = event(100);
        store.provision(event, 100).block(Duration.ofSeconds(20));
        var request = new ReservationRequest("courtesy-" + event, event, "guest", List.of("1", "2", "17"),
                clock.instant(), clock.instant().plusSeconds(600), "COMPLIMENTARY", "admin", "Invited guest");
        var result = inventory.reserve(request).block(Duration.ofSeconds(10));
        assertNotNull(result); assertEquals(TicketStatus.COMPLIMENTARY, result.status());
        var available = store.availability(event).block(Duration.ofSeconds(10));
        assertNotNull(available); assertEquals(97, available.available()); assertEquals(3, available.complimentary()); assertEquals(0, available.sold());
        assertThrows(InventoryException.class, () -> inventory.confirm(request.orderId(), "guest", "confirm:courtesy").block(Duration.ofSeconds(10)));
    }

    @Test void twoConfirmationsAndExpiryRacingConserveAllCounters() {
        String event = event(10);
        var request = request("final-race-" + event, event, List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
        var reserved = inventory.reserve(request).block(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(600));
        Flux.merge(inventory.confirm(request.orderId(), "customer", "confirm:" + request.orderId()).onErrorResume(error -> Mono.empty()),
                store.transition(reserved, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null, clock.instant()).onErrorResume(error -> Mono.empty()))
                .then().block(Duration.ofSeconds(10));
        var result = store.availability(event).block(Duration.ofSeconds(10));
        assertNotNull(result); assertEquals(10, result.available()); assertEquals(0, result.reserved()); assertEquals(0, result.sold());
    }

    private String event(int capacity) {
        // Each test owns an event; the shared local table is deleted only by this class.
        String event = UUID.randomUUID().toString();
        inventory.provision(event, capacity).block(Duration.ofSeconds(20));
        return event;
    }

    @Test void noWorkerIsRequiredToReclaimTheEntireExpiredOwner() {
        String event = event(3);
        var oldRequest = request("lazy-old-" + event, event, List.of("1", "2"));
        var old = inventory.reserve(oldRequest).block(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(599));
        assertEquals(TicketStatus.RESERVED, inventory.get(oldRequest.orderId()).block(Duration.ofSeconds(10)).status());
        clock.advance(Duration.ofSeconds(1));
        var replacement = inventory.reserve(request("lazy-new-" + event, event, List.of("1"))).block(Duration.ofSeconds(10));
        assertEquals(TicketStatus.RESERVED, replacement.status());
        var expired = inventory.get(oldRequest.orderId()).block(Duration.ofSeconds(10));
        assertEquals("RESERVATION_EXPIRED", expired.resolution());
        assertTrue(expired.activeTicketIds().isEmpty());
        var result = store.availability(event).block(Duration.ofSeconds(10));
        assertEquals(2, result.available()); assertEquals(1, result.reserved());
        assertThrows(InventoryException.class, () -> store.transition(old, TicketStatus.AVAILABLE, "RESERVATION_EXPIRED", null,
                clock.instant()).block(Duration.ofSeconds(10)));
    }

    @Test void availabilityRejectsLagBeyondFiveSecondsWithoutRelyingOnAnEventualIndex() {
        String event = event(2);
        var request = request("lag-" + event, event, List.of("1", "2"));
        inventory.reserve(request).block(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(605));
        assertEquals(2, store.availability(event).block(Duration.ofSeconds(10)).reserved());
        clock.advance(Duration.ofMillis(1));
        var failure = assertThrows(InventoryException.class, () -> store.availability(event).block(Duration.ofSeconds(10)));
        assertEquals("INVENTORY_STALE", failure.code()); assertEquals(503, failure.status());
        assertEquals(TicketStatus.AVAILABLE, inventory.get(request.orderId()).block(Duration.ofSeconds(10)).status());
        assertEquals(2, store.availability(event).block(Duration.ofSeconds(10)).available());
    }

    @Test void twoReplicaReadersExpireExactlyOnceAndLeaseOwnershipSurvivesTakeover() {
        String event = event(2);
        var request = request("replicas-" + event, event, List.of("1", "2"));
        inventory.reserve(request).block(Duration.ofSeconds(10));
        var replica = new InventoryUseCase(new DynamoDBTemplateAdapter(client, table, clock), clock);
        clock.advance(Duration.ofSeconds(600));
        var observations = Flux.merge(inventory.get(request.orderId()), replica.get(request.orderId()))
                .collectList().block(Duration.ofSeconds(10));
        assertEquals(2, observations.size());
        assertTrue(observations.stream().allMatch(value -> value.inventoryVersion() == 2 && value.activeTicketIds().isEmpty()));
        var outboxA = new DynamoOutboxStore(client, table);
        var outboxB = new DynamoOutboxStore(client, table);
        var message = new OutboxMessage("OUTBOX#reservation:" + request.orderId() + ":2", "ORDERS", "{}", null);
        var owners = Flux.merge(outboxA.claim(message, clock.instant()), outboxB.claim(message, clock.instant()))
                .collectList().block(Duration.ofSeconds(10));
        assertEquals(1, owners.size());
        clock.advance(Duration.ofSeconds(30));
        var takeover = outboxB.claim(message, clock.instant()).block(Duration.ofSeconds(10));
        assertNotEquals(owners.getFirst().leaseId(), takeover.leaseId());
        assertThrows(ConditionalCheckFailedException.class, () -> outboxA.sent(owners.getFirst(), clock.instant()).block(Duration.ofSeconds(10)));
        outboxB.sent(takeover, clock.instant()).block(Duration.ofSeconds(10));
        assertNull(outboxA.claim(message, clock.instant().plusSeconds(30)).block(Duration.ofSeconds(10)));
    }

    private ReservationRequest request(String order, String event, List<String> tickets) {
        return new ReservationRequest(order, event, "customer", tickets, clock.instant(), clock.instant().plusSeconds(600), "PURCHASE", null, null);
    }

    @Test void durableTimerSurvivesRestartAndDuplicatesCannotReleaseTheNextOwnerOrSoldSeats() {
        String event = event(2);
        var request = request("timer-" + event, event, List.of("1", "2"));
        inventory.reserve(request).block(Duration.ofSeconds(10));
        var restarted = new DynamoDBTemplateAdapter(client, table, clock);
        var timer = restarted.get("OUTBOX#expiry:" + request.orderId(), "META").block(Duration.ofSeconds(10));
        assertNotNull(timer); assertEquals("EXPIRATIONS", DynamoItems.string(timer, "destination"));
        var early = assertThrows(InventoryException.class, () -> inventory.expireReservation(request.orderId(), request.expiresAt()).block(Duration.ofSeconds(10)));
        assertEquals("RESERVATION_NOT_DUE", early.code());
        assertThrows(InventoryException.class, () -> inventory.expireReservation(request.orderId(), request.expiresAt().plusSeconds(1)).block(Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(600));
        var expired = inventory.expireReservation(request.orderId(), request.expiresAt()).block(Duration.ofSeconds(10));
        assertEquals(expired, inventory.expireReservation(request.orderId(), request.expiresAt()).block(Duration.ofSeconds(10)));
        var next = request("next-timer-" + event, event, List.of("1"));
        inventory.reserve(next).block(Duration.ofSeconds(10));
        inventory.expireReservation(request.orderId(), request.expiresAt()).block(Duration.ofSeconds(10));
        assertEquals(TicketStatus.RESERVED, inventory.get(next.orderId()).block(Duration.ofSeconds(10)).status());
        var sold = inventory.confirm(next.orderId(), "customer", "confirm-" + next.orderId()).block(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(600));
        assertEquals(sold, inventory.expireReservation(next.orderId(), next.expiresAt()).block(Duration.ofSeconds(10)));
        assertEquals(1, restarted.availability(event).block(Duration.ofSeconds(10)).sold());
    }

    @Test void recoveryUsesOneFencedCoordinatorAndTraversesMultiplePages() {
        String event = event(35);
        var orderIds = java.util.stream.IntStream.iterate(0, index -> index + 1).mapToObj(index -> "recover-" + event + "-" + index)
                .filter(id -> Math.floorMod(id.hashCode(), 16) == 0).limit(35).toList();
        Flux.range(0, 35).concatMap(index -> inventory.reserve(request(orderIds.get(index), event, List.of(Integer.toString(index + 1)))))
                .then().block(Duration.ofSeconds(40));
        clock.advance(Duration.ofSeconds(600));
        var replica = new DynamoDBTemplateAdapter(client, table, clock);
        assertTrue(store.claimRecovery("recovery-a", clock.instant()).block(Duration.ofSeconds(10)).acquired());
        assertFalse(replica.claimRecovery("recovery-b", clock.instant()).block(Duration.ofSeconds(10)).acquired());
        assertEquals(0, inventory.recoverExpired(10, ignored -> { }).block(Duration.ofSeconds(10)));
        store.releaseRecovery("recovery-a").block(Duration.ofSeconds(10));
        assertEquals(0, inventory.recoverExpired(10, ignored -> { }).block(Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(60));
        assertTrue(replica.claimRecovery("recovery-b", clock.instant()).block(Duration.ofSeconds(10)).acquired());
        store.releaseRecovery("recovery-a").block(Duration.ofSeconds(10));
        assertEquals("recovery-b", DynamoItems.string(store.get("LEASE#EXPIRY_SWEEP", "META").block(Duration.ofSeconds(10)), "leaseId"));
        replica.releaseRecovery("recovery-b").block(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(60));
        assertTrue(inventory.recoverExpired(10, ignored -> { }).block(Duration.ofSeconds(45)) >= 35);
        assertEquals(35, store.availability(event).block(Duration.ofSeconds(10)).available());
        assertEquals(0, inventory.recoverExpired(10, ignored -> { }).block(Duration.ofSeconds(10)));
    }

    @Test void outboxCoordinatorHasOneOwnerAndTakeoverAtThirtySeconds() {
        var first = new DynamoOutboxStore(client, table);
        var second = new DynamoOutboxStore(client, table);
        assertTrue(first.coordinate("dispatch-a", clock.instant()).block(Duration.ofSeconds(10)).acquired());
        assertFalse(second.coordinate("dispatch-b", clock.instant()).block(Duration.ofSeconds(10)).acquired());
        clock.advance(Duration.ofSeconds(29));
        assertFalse(second.coordinate("dispatch-b", clock.instant()).block(Duration.ofSeconds(10)).acquired());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(second.coordinate("dispatch-b", clock.instant()).block(Duration.ofSeconds(10)).acquired());
        assertFalse(first.coordinate("dispatch-a", clock.instant()).block(Duration.ofSeconds(10)).acquired());
    }

    private static List<KeySchemaElement> keys(String partition, String sort) {
        return List.of(KeySchemaElement.builder().attributeName(partition).keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName(sort).keyType(KeyType.RANGE).build());
    }

    private static GlobalSecondaryIndex index(String name) {
        return GlobalSecondaryIndex.builder().indexName(name).keySchema(keys(name + "pk", name + "sk"))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant now) { this.now = new AtomicReference<>(now); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
