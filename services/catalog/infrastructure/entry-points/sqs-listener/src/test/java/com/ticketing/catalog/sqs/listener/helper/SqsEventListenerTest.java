package com.ticketing.catalog.sqs.listener.helper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.ticketing.catalog.sqs.listener.SqsEventProcessor;
import com.ticketing.catalog.sqs.listener.config.SqsListenerProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.concurrent.CompletableFuture;

class SqsEventListenerTest {

    @Mock
    private SqsAsyncClient asyncClient;

    private SqsListenerProperties sqsProperties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        sqsProperties = new SqsListenerProperties("us-east-1", "http://localhost:4566",
            "http://localhost:4566/00000000000/queueName", 20, 30, 10, 1);

        var message = Message.builder().body("message").build();
        var deleteMessageResponse = DeleteMessageResponse.builder().build();
        var messageResponse = ReceiveMessageResponse.builder().messages(message).build();

        when(asyncClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(messageResponse));
        when(asyncClient.deleteMessage(any(DeleteMessageRequest.class)))
            .thenReturn(CompletableFuture.completedFuture(deleteMessageResponse));
    }

    @Test
    void listenerTest() {
        SqsEventProcessor processor = mock(SqsEventProcessor.class);
        when(processor.apply(any(Message.class))).thenReturn(Mono.empty());
        var sqsListener = SqsEventListener.builder().client(asyncClient).properties(sqsProperties)
            .processor(processor).operation("operation").build();

        Flux<Void> flow = ReflectionTestUtils.invokeMethod(sqsListener, "listen");
        StepVerifier.create(flow).verifyComplete();
    }

    @Test
    void failedMessageIsRetriedWithoutLoggingItsBodyOrCancellingHealthyMessages() {
        var rejected = Message.builder().messageId("rejected").receiptHandle("bad-handle")
            .body("private payload").build();
        var accepted = Message.builder().messageId("accepted").receiptHandle("good-handle").body("valid")
            .build();
        when(asyncClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(CompletableFuture
            .completedFuture(ReceiveMessageResponse.builder().messages(rejected, accepted).build()));
        var logger = (Logger) LoggerFactory.getLogger(SqsEventListener.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        var listener = SqsEventListener.builder().client(asyncClient).properties(sqsProperties)
            .operation("test")
            .processor(message -> "rejected".equals(message.messageId())
                ? Mono.error(new IllegalArgumentException("private payload"))
                : Mono.empty())
            .build();
        try {
            Flux<Void> flow = ReflectionTestUtils.invokeMethod(listener, "listen");
            StepVerifier.create(flow).verifyComplete();
            verify(asyncClient).deleteMessage(
                argThat((DeleteMessageRequest request) -> "good-handle".equals(request.receiptHandle())));
            verify(asyncClient, never()).deleteMessage(
                argThat((DeleteMessageRequest request) -> "bad-handle".equals(request.receiptHandle())));
            assertFalse(captured.list.isEmpty());
            var failureLog = captured.list.stream()
                .filter(entry -> entry.getMessage().equals("Message retained for retry")).findFirst()
                .orElseThrow();
            assertTrue(failureLog.getKeyValuePairs().stream()
                .anyMatch(pair -> pair.key.equals("messageId") && pair.value.equals("rejected")));
            assertTrue(failureLog.getKeyValuePairs().stream().anyMatch(
                pair -> pair.key.equals("causeType") && pair.value.equals("IllegalArgumentException")));
            captured.list.forEach(entry -> {
                assertFalse(entry.getFormattedMessage().contains("private payload"));
                assertNull(entry.getThrowableProxy());
            });
        } finally {
            listener.close();
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
