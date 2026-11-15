package com.ticketing.inventory.sqs.listener;

import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.ReservationRequest;
import com.ticketing.inventory.usecase.InventoryUseCase;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class SQSProcessor implements Function<Message, Mono<Void>> {
    private final InventoryUseCase inventory;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public SQSProcessor(InventoryUseCase inventory) { this.inventory = inventory; }

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.defer(() -> {
            var envelope = json.readTree(message.body());
            if (envelope.path("schemaVersion").asInt() != 1 || envelope.path("messageId").asString().isBlank()) {
                return Mono.error(new InventoryException("INVALID_MESSAGE_SCHEMA", 400));
            }
            var payload = envelope.path("payload");
            return switch (envelope.path("type").asString()) {
                case "ProvisionInventory", "EventCreated" -> inventory.provision(payload.path("eventId").asString(), payload.path("capacity").asInt());
                case "PurchaseRequested", "ComplimentaryRequested" -> inventory.reserve(json.treeToValue(payload, ReservationRequest.class)).then();
                case "ExpireReservation" -> inventory.expireReservation(payload.path("orderId").asString(),
                        java.time.Instant.parse(payload.path("expiresAt").asString()), SQSProcessor::logExpiryLag)
                        .doOnNext(reservation -> logExpiryOutcome(reservation, envelope.path("messageId").asString())).then();
                case "SweepExpiredReservations" -> inventory.recoverExpired(100, SQSProcessor::logExpiryLag).then();
                case "ConfirmPurchase" -> inventory.confirm(payload.path("orderId").asString(), payload.path("customerId").asString(),
                        payload.path("commandId").asString()).then().onErrorResume(InventoryException.class, error ->
                        "RESERVATION_EXPIRED".equals(error.code()) || "INVALID_TRANSITION".equals(error.code())
                                ? Mono.empty() : Mono.error(error));
                default -> Mono.error(new InventoryException("UNKNOWN_MESSAGE_TYPE", 400));
            };
        });
    }

    public static void logExpiryLag(long lag) {
        org.slf4j.LoggerFactory.getLogger(SQSProcessor.class).atInfo().addKeyValue("metric", "expiration_lag_seconds")
                .addKeyValue("value", lag).log("Reservation expiry lag");
    }

    private static void logExpiryOutcome(com.ticketing.inventory.model.Reservation reservation, String messageId) {
        org.slf4j.LoggerFactory.getLogger(SQSProcessor.class).atInfo().addKeyValue("operation", "ExpireReservation")
                .addKeyValue("reservationId", reservation.orderId()).addKeyValue("message_id", messageId)
                .addKeyValue("outcome", "RESERVATION_EXPIRED".equals(reservation.resolution()) ? "expired" : "noop")
                .log("Expiration command processed");
    }
}
