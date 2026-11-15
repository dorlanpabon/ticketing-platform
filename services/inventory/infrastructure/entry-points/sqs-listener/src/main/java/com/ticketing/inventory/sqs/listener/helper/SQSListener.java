package com.ticketing.inventory.sqs.listener.helper;

import com.ticketing.inventory.sqs.listener.SQSProcessor;
import com.ticketing.inventory.usecase.InventoryUseCase;
import com.ticketing.inventory.usecase.OutboxUseCase;
import com.ticketing.inventory.model.DispatchBatch;
import com.ticketing.inventory.model.InventoryException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

@Component
@ConditionalOnProperty(name = "inventory.workers.enabled", havingValue = "true")
public final class SQSListener implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(SQSListener.class.getName());
    private final SqsAsyncClient sqs;
    private final SQSProcessor processor;
    private final InventoryUseCase inventory;
    private final OutboxUseCase outbox;
    private final String queue;
    private final String expirationsQueue;
    private final boolean localRecovery;
    private final java.time.Clock clock;
    private final tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<Disposable.Composite> subscriptions = new java.util.concurrent.atomic.AtomicReference<>();

    public SQSListener(SqsAsyncClient sqs, SQSProcessor processor, InventoryUseCase inventory,
            OutboxUseCase outbox, @Value("${inventory.commands-queue}") String queue,
            @Value("${inventory.expirations-queue}") String expirationsQueue,
            @Value("${spring.profiles.active:}") String activeProfiles,
            @Value("${inventory.local-recovery.enabled:false}") boolean recoveryEnabled, java.time.Clock clock) {
        this.sqs = sqs; this.processor = processor; this.inventory = inventory; this.outbox = outbox; this.queue = queue;
        this.expirationsQueue = expirationsQueue;
        this.localRecovery = recoveryEnabled && java.util.Arrays.asList(activeProfiles.split(",")).contains("local");
        this.clock = clock;
    }

    @Override public void start() {
        if (!running.compareAndSet(false, true)) return;
        var active = Disposables.composite();
        subscriptions.set(active);
        active.add(consume(queue).subscribe());
        active.add(consume(expirationsQueue).subscribe());
        var backoff = new DispatchBackoff();
        active.add(Mono.defer(() -> outbox.dispatch(100).onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "Outbox retry scheduled: {0}", error.getClass().getSimpleName());
                    return Mono.just(new DispatchBatch(0, 0));
                }).flatMap(batch -> Mono.delay(backoff.next(batch)))).repeat().subscribe());
        if (localRecovery) active.add(Flux.interval(Duration.ZERO, Duration.ofMinutes(1)).onBackpressureDrop()
                .concatMap(tick -> inventory.recoverExpired(100, SQSProcessor::logExpiryLag).onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "Expiration recovery retry scheduled: {0}", error.getClass().getSimpleName());
                    return Mono.empty();
                }), 1).subscribe());
    }

    private Flux<Void> consume(String sourceQueue) {
        return Flux.defer(() -> receive(sourceQueue).onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "SQS receive failed: {0}", error.getClass().getSimpleName());
                    return Mono.delay(Duration.ofSeconds(1)).thenMany(Flux.empty());
                })).repeat();
    }

    private Flux<Void> receive(String sourceQueue) {
        return Mono.fromFuture(() -> sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(sourceQueue)
                        .waitTimeSeconds(20).maxNumberOfMessages(10).visibilityTimeout(60).build()))
                .flatMapMany(response -> Flux.fromIterable(response.messages()))
                .flatMap(message -> process(sourceQueue, message), 10);
    }

    private Mono<Void> process(String sourceQueue, Message message) {
        Mono<Void> work = processor.apply(message)
                .then(Mono.fromFuture(() -> sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(sourceQueue)
                        .receiptHandle(message.receiptHandle()).build()))).then().cache();
        Mono<Void> heartbeat = Flux.interval(Duration.ofSeconds(20))
                .concatMap(tick -> Mono.fromFuture(() -> sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                        .queueUrl(sourceQueue).receiptHandle(message.receiptHandle()).visibilityTimeout(60).build())))
                .takeUntilOther(work).then();
        return work.and(heartbeat).onErrorResume(error -> {
            LOG.log(System.Logger.Level.WARNING, "Message retained for retry id={0} cause={1}", message.messageId(), error.getClass().getSimpleName());
            if (error instanceof InventoryException failure && "RESERVATION_NOT_DUE".equals(failure.code())) {
                return deferEarlyTimer(sourceQueue, message);
            }
            return Mono.empty();
        });
    }

    private Mono<Void> deferEarlyTimer(String sourceQueue, Message message) {
        return Mono.defer(() -> {
            var deadline = java.time.Instant.parse(json.readTree(message.body()).path("payload").path("expiresAt").asText());
            var remaining = Duration.between(clock.instant(), deadline);
            int seconds = (int) Math.max(1, Math.min(600, remaining.toSeconds() + (remaining.getNano() > 0 ? 1 : 0)));
            return Mono.fromFuture(() -> sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(sourceQueue).receiptHandle(message.receiptHandle()).visibilityTimeout(seconds).build())).then();
        }).onErrorResume(error -> {
            LOG.log(System.Logger.Level.WARNING, "Early timer retains original visibility: {0}", error.getClass().getSimpleName());
            return Mono.empty();
        });
    }

    @Override public void stop() {
        running.set(false);
        var active = subscriptions.getAndSet(null);
        if (active != null) active.dispose();
    }
    @Override public boolean isRunning() { return running.get(); }
}
