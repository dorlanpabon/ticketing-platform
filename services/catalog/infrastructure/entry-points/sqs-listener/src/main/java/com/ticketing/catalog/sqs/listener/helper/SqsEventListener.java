package com.ticketing.catalog.sqs.listener.helper;

import com.ticketing.catalog.sqs.listener.config.SqsListenerProperties;
import com.ticketing.catalog.sqs.listener.utils.ListenerConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Runs SQS long polling and manages the lifecycle of message processing.
 */
public class SqsEventListener implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SqsEventListener.class);
    private final SqsAsyncClient client;
    private final SqsListenerProperties properties;
    private final Function<Message, Mono<Void>> processor;
    private String operation;
    private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();
    private final Scheduler scheduler = Schedulers.newParallel(ListenerConstants.SCHEDULER_NAME, 1);

    public SqsEventListener(final SqsAsyncClient client, final SqsListenerProperties properties,
        final Function<Message, Mono<Void>> processor, final String operation) {
        this.client = client;
        this.properties = properties;
        this.processor = processor;
        this.operation = operation;
    }

    public SqsEventListener start() {
        this.operation = ListenerConstants.OPERATION_PREFIX + properties.queueUrl();
        for (var i = 0; i < properties.numberOfThreads(); i++) {
            subscriptions.add(listenRetryRepeat().subscribeOn(scheduler).subscribe());
        }
        return this;
    }

    @Override
    public void close() {
        subscriptions.forEach(Disposable::dispose);
        scheduler.dispose();
    }

    private Flux<Void> listenRetryRepeat() {
        return listen().onErrorResume(e -> Mono.delay(Duration.ofSeconds(1)).then(Mono.empty())).repeat();
    }

    private Flux<Void> listen() {
        return getMessages().flatMap(message -> processor.apply(message)
            .name(ListenerConstants.OPERATION_NAME).tag(ListenerConstants.OPERATION_TAG, operation).metrics()
            .then(Mono.defer(() -> confirm(message))).onErrorResume(error -> {
                log.atWarn().addKeyValue(ListenerConstants.MESSAGE_ID_KEY, message.messageId())
                    .addKeyValue(ListenerConstants.CAUSE_TYPE_KEY, error.getClass().getSimpleName())
                    .log(ListenerConstants.MESSAGE_RETRY_LOG);
                return Mono.empty();
            }), properties.maxNumberOfMessages());
    }

    private Mono<Void> confirm(Message message) {
        return Mono.fromCallable(() -> getDeleteMessageRequest(message.receiptHandle()))
            .flatMap(request -> Mono.fromFuture(client.deleteMessage(request))).then();
    }

    private Flux<Message> getMessages() {
        return Mono.fromCallable(this::getReceiveMessageRequest)
            .flatMap(request -> Mono.fromFuture(client.receiveMessage(request)))
            .doOnNext(response -> log.atDebug()
                .addKeyValue(ListenerConstants.MESSAGE_COUNT_KEY, response.messages().size())
                .log(ListenerConstants.MESSAGES_RECEIVED_LOG))
            .flatMapMany(response -> Flux.fromIterable(response.messages()));
    }

    private ReceiveMessageRequest getReceiveMessageRequest() {
        return ReceiveMessageRequest.builder().queueUrl(properties.queueUrl())
            .maxNumberOfMessages(properties.maxNumberOfMessages())
            .waitTimeSeconds(properties.waitTimeSeconds())
            .visibilityTimeout(properties.visibilityTimeoutSeconds()).build();
    }

    private DeleteMessageRequest getDeleteMessageRequest(String receiptHandle) {
        return DeleteMessageRequest.builder().queueUrl(properties.queueUrl()).receiptHandle(receiptHandle)
            .build();
    }

    public static class SqsEventListenerBuilder {
        private SqsAsyncClient sqsClient;
        private SqsListenerProperties listenerProperties;
        private Function<Message, Mono<Void>> messageProcessor;
        private String operationName;

        SqsEventListenerBuilder() {
        }

        public SqsEventListener.SqsEventListenerBuilder client(final SqsAsyncClient client) {
            this.sqsClient = client;
            return this;
        }

        public SqsEventListener.SqsEventListenerBuilder properties(final SqsListenerProperties properties) {
            this.listenerProperties = properties;
            return this;
        }

        public SqsEventListener.SqsEventListenerBuilder processor(
            final Function<Message, Mono<Void>> processor) {
            this.messageProcessor = processor;
            return this;
        }

        public SqsEventListener.SqsEventListenerBuilder operation(final String operation) {
            this.operationName = operation;
            return this;
        }

        public SqsEventListener build() {
            return new SqsEventListener(this.sqsClient, this.listenerProperties, this.messageProcessor,
                this.operationName);
        }
    }

    public static SqsEventListener.SqsEventListenerBuilder builder() {
        return new SqsEventListener.SqsEventListenerBuilder();
    }
}
