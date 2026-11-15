package com.ticketing.orders.sqs.listener.helper;

import com.ticketing.orders.sqs.listener.config.SQSProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.function.Function;

public class SQSListener implements AutoCloseable {
    private static final org.apache.logging.log4j.Logger log = org.apache.logging.log4j.LogManager.getLogger(SQSListener.class);
    private final SqsAsyncClient client;
    private final SQSProperties properties;
    private final Function<Message, Mono<Void>> processor;
    private final String operation;
    private final java.util.List<reactor.core.Disposable> subscriptions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Scheduler scheduler = Schedulers.newParallel("sqs-pool", 1);

    public SQSListener(final SqsAsyncClient client, final SQSProperties properties, final Function<Message, Mono<Void>> processor, final String operation) {
        this.client = client;
        this.properties = properties;
        this.processor = processor;
        this.operation = operation == null ? "MessageFrom:" + properties.queueUrl() : operation;
    }

    public SQSListener start() {
        for (var i = 0; i < properties.numberOfThreads(); i++) {
            subscriptions.add(listenRetryRepeat().subscribeOn(scheduler).subscribe());
        }
        return this;
    }

    @Override
    public void close() {
        subscriptions.forEach(reactor.core.Disposable::dispose);
        scheduler.dispose();
    }

    private Flux<Void> listenRetryRepeat() {
        return listen()
                .onErrorResume(e -> Mono.delay(java.time.Duration.ofSeconds(1)).then(Mono.empty()))
                .repeat();
    }

    private Flux<Void> listen() {
        return getMessages()
                .flatMap(message -> processor.apply(message)
                        .name("async_operation")
                        .tag("operation", operation)
                        .metrics()
                        .then(Mono.defer(() -> confirm(message)))
                        .onErrorResume(error -> {
                            log.warn("Message retained for retry id={} cause={}",message.messageId(),error.getClass().getSimpleName());
                            return Mono.empty();
                        }), properties.maxNumberOfMessages());
    }

    private Mono<Void> confirm(Message message) {
        return Mono.fromCallable(() -> getDeleteMessageRequest(message.receiptHandle()))
                .flatMap(request -> Mono.fromFuture(client.deleteMessage(request)))
                .then();
    }

    private Flux<Message> getMessages() {
        return Mono.fromCallable(this::getReceiveMessageRequest)
                .flatMap(request -> Mono.fromFuture(client.receiveMessage(request)))
                .doOnNext(response -> log.debug("{} received messages from sqs", response.messages().size()))
                .flatMapMany(response -> Flux.fromIterable(response.messages()));
    }

    private ReceiveMessageRequest getReceiveMessageRequest() {
        return ReceiveMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .maxNumberOfMessages(properties.maxNumberOfMessages())
                .waitTimeSeconds(properties.waitTimeSeconds())
                .visibilityTimeout(properties.visibilityTimeoutSeconds())
                .build();
    }

    private DeleteMessageRequest getDeleteMessageRequest(String receiptHandle) {
        return DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(receiptHandle)
                .build();
    }

    public static class SQSListenerBuilder {
        private SqsAsyncClient sqsClient;
        private SQSProperties listenerProperties;
        private Function<Message, Mono<Void>> messageProcessor;
        private String operationName;

        SQSListenerBuilder() {
        }

        public SQSListener.SQSListenerBuilder client(final SqsAsyncClient client) {
            this.sqsClient = client;
            return this;
        }

        public SQSListener.SQSListenerBuilder properties(final SQSProperties properties) {
            this.listenerProperties = properties;
            return this;
        }

        public SQSListener.SQSListenerBuilder processor(final Function<Message, Mono<Void>> processor) {
            this.messageProcessor = processor;
            return this;
        }

        public SQSListener.SQSListenerBuilder operation(final String operation) {
            this.operationName = operation;
            return this;
        }

        public SQSListener build() {
            return new SQSListener(this.sqsClient, this.listenerProperties, this.messageProcessor, this.operationName);
        }

    }

    public static SQSListener.SQSListenerBuilder builder() {
        return new SQSListener.SQSListenerBuilder();
    }
}
