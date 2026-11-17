package com.ticketing.catalog.sqs.sender;

import com.ticketing.catalog.sqs.sender.config.SqsPublisherProperties;
import com.ticketing.catalog.sqs.sender.utils.PublisherConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

@Service
public class SqsCommandPublisher /*implements SomeGateway*/ {
    private static final Logger log = LoggerFactory.getLogger(SqsCommandPublisher.class);
    private final SqsPublisherProperties properties;
    private final SqsAsyncClient client;

    public SqsCommandPublisher(SqsAsyncClient client, SqsPublisherProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public Mono<String> send(String message) {
        return Mono.fromCallable(() -> buildRequest(message))
            .flatMap(request -> Mono.fromFuture(client.sendMessage(request)))
            .doOnNext(
                response -> log.atDebug().addKeyValue(PublisherConstants.MESSAGE_ID_KEY, response.messageId())
                    .log(PublisherConstants.MESSAGE_SENT_LOG))
            .map(SendMessageResponse::messageId);
    }

    private SendMessageRequest buildRequest(String message) {
        return SendMessageRequest.builder().queueUrl(properties.queueUrl()).messageBody(message).build();
    }
}
