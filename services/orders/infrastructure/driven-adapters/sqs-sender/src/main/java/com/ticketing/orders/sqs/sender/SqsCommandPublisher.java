package com.ticketing.orders.sqs.sender;

import com.ticketing.orders.sqs.sender.config.SqsPublisherProperties;
import com.ticketing.orders.sqs.sender.utils.SqsConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

@Service("SQSSender")
public class SqsCommandPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(SqsCommandPublisher.class);
    private final SqsPublisherProperties properties;
    private final SqsAsyncClient client;

    public SqsCommandPublisher(SqsAsyncClient client, SqsPublisherProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public Mono<String> send(String message) {
        return Mono.fromCallable(() -> buildRequest(message))
            .flatMap(request -> Mono.fromFuture(() -> client.sendMessage(request)))
            .filter(response -> response.messageId() != null && !response.messageId().isBlank())
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("SQS did not return a message ID")))
            .map(SendMessageResponse::messageId).doOnNext(messageId -> LOG.atDebug()
                .addKeyValue(SqsConstants.MESSAGE_ID, messageId).log("Message sent"));
    }

    private SendMessageRequest buildRequest(String message) {
        return SendMessageRequest.builder().queueUrl(properties.queueUrl()).messageBody(message).build();
    }
}
