package com.ticketing.inventory.sqs.listener;

import com.ticketing.inventory.sqs.listener.utils.SqsListenerConstants;
import com.ticketing.inventory.sqs.listener.dto.ReservationPayload;
import com.ticketing.inventory.sqs.listener.mapper.ReservationCommandMapper;
import org.slf4j.Logger;
import com.ticketing.inventory.model.InventoryException;
import com.ticketing.inventory.model.Reservation;
import com.ticketing.inventory.usecase.InventoryUseCase;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.sqs.model.Message;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.function.Function;

/**
 * Routes incoming inventory commands to the appropriate use case.
 */
@Service
public final class SqsCommandProcessor implements Function<Message, Mono<Void>> {
    private static final Logger LOG = LoggerFactory.getLogger(SqsCommandProcessor.class);

    private final InventoryUseCase inventory;
    private final ReservationCommandMapper commandMapper;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public SqsCommandProcessor(InventoryUseCase inventory, ReservationCommandMapper commandMapper) {
        this.inventory = inventory;
        this.commandMapper = commandMapper;
    }

    @Override
    public Mono<Void> apply(Message message) {
        return Mono.defer(() -> {
            var envelope = json.readTree(message.body());
            if (envelope.path(SqsListenerConstants.SCHEMA_VERSION).asInt() != 1
                || envelope.path(SqsListenerConstants.MESSAGE_ID).asString().isBlank()) {
                return Mono.error(new InventoryException(SqsListenerConstants.INVALID_MESSAGE_SCHEMA, 400));
            }
            var payload = envelope.path(SqsListenerConstants.PAYLOAD);
            return switch (envelope.path(SqsListenerConstants.TYPE).asString()) {
            case SqsListenerConstants.PROVISION_INVENTORY, SqsListenerConstants.EVENT_CREATED ->
                inventory.provision(payload.path(SqsListenerConstants.EVENT_ID).asString(),
                    payload.path(SqsListenerConstants.CAPACITY).asInt());
            case SqsListenerConstants.PURCHASE_REQUESTED, SqsListenerConstants.COMPLIMENTARY_REQUESTED ->
                inventory.reserve(commandMapper.toIntent(json.treeToValue(payload, ReservationPayload.class)))
                    .then();
            case SqsListenerConstants.EXPIRE_RESERVATION -> inventory
                .expireReservation(payload.path(SqsListenerConstants.ORDER_ID).asString(),
                    Instant.parse(payload.path(SqsListenerConstants.EXPIRES_AT).asString()),
                    SqsCommandProcessor::logExpiryLag)
                .doOnNext(reservation -> logExpiryOutcome(reservation,
                    envelope.path(SqsListenerConstants.MESSAGE_ID).asString()))
                .then();
            case SqsListenerConstants.SWEEP_EXPIRED_RESERVATIONS ->
                inventory.recoverExpired(100, SqsCommandProcessor::logExpiryLag).then();
            case SqsListenerConstants.CONFIRM_PURCHASE -> inventory
                .confirm(payload.path(SqsListenerConstants.ORDER_ID).asString(),
                    payload.path(SqsListenerConstants.CUSTOMER_ID).asString(),
                    payload.path(SqsListenerConstants.COMMAND_ID).asString())
                .then().onErrorResume(InventoryException.class,
                    error -> SqsListenerConstants.RESERVATION_EXPIRED.equals(error.code())
                        || SqsListenerConstants.INVALID_TRANSITION.equals(error.code()) ? Mono.empty()
                            : Mono.error(error));
            default -> Mono.error(new InventoryException(SqsListenerConstants.UNKNOWN_MESSAGE_TYPE, 400));
            };
        });
    }

    public static void logExpiryLag(long lag) {
        LOG.atInfo().addKeyValue(SqsListenerConstants.METRIC, SqsListenerConstants.EXPIRATION_LAG_SECONDS)
            .addKeyValue(SqsListenerConstants.VALUE, lag).log(SqsListenerConstants.EXPIRY_LAG_MESSAGE);
    }

    private static void logExpiryOutcome(Reservation reservation, String messageId) {
        LOG.atInfo().addKeyValue(SqsListenerConstants.OPERATION, SqsListenerConstants.EXPIRE_RESERVATION)
            .addKeyValue(SqsListenerConstants.RESERVATION_ID, reservation.orderId())
            .addKeyValue(SqsListenerConstants.MESSAGE_ID_LOG, messageId)
            .addKeyValue(SqsListenerConstants.OUTCOME,
                SqsListenerConstants.RESERVATION_EXPIRED.equals(reservation.resolution())
                    ? SqsListenerConstants.EXPIRED_OUTCOME
                    : SqsListenerConstants.NOOP_OUTCOME)
            .log(SqsListenerConstants.EXPIRY_PROCESSED_MESSAGE);
    }
}
