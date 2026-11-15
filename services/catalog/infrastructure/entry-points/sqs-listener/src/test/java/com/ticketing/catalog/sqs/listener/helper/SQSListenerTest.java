package com.ticketing.catalog.sqs.listener.helper;

import com.ticketing.catalog.sqs.listener.SQSProcessor;
import com.ticketing.catalog.sqs.listener.config.SQSProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import reactor.core.publisher.Mono;

class SQSListenerTest {

    @Mock
    private SqsAsyncClient asyncClient;

    private SQSProperties sqsProperties;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        sqsProperties = new SQSProperties(
                "us-east-1",
                "http://localhost:4566",
                "http://localhost:4566/00000000000/queueName",
                20,
                30,
                10,
                1
        );

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
        SQSProcessor processor = mock(SQSProcessor.class);
        when(processor.apply(any(Message.class))).thenReturn(Mono.empty());
        var sqsListener = SQSListener.builder()
                .client(asyncClient)
                .properties(sqsProperties)
                .processor(processor)
                .operation("operation")
                .build();

        Flux<Void> flow = ReflectionTestUtils.invokeMethod(sqsListener, "listen");
        StepVerifier.create(flow).verifyComplete();
    }

    @Test
    void failedMessageIsRetriedWithoutLoggingItsBodyOrCancellingHealthyMessages() {
        var rejected = Message.builder().messageId("rejected").receiptHandle("bad-handle").body("private payload").build();
        var accepted = Message.builder().messageId("accepted").receiptHandle("good-handle").body("valid").build();
        when(asyncClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(
                ReceiveMessageResponse.builder().messages(rejected, accepted).build()));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SQSListener.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        var listener = SQSListener.builder().client(asyncClient).properties(sqsProperties).operation("test")
                .processor(message -> "rejected".equals(message.messageId())
                        ? Mono.error(new IllegalArgumentException("private payload")) : Mono.empty()).build();
        try {
            Flux<Void> flow = ReflectionTestUtils.invokeMethod(listener, "listen");
            StepVerifier.create(flow).verifyComplete();
            org.mockito.Mockito.verify(asyncClient).deleteMessage(org.mockito.ArgumentMatchers.argThat(
                    (DeleteMessageRequest request) -> "good-handle".equals(request.receiptHandle())));
            org.mockito.Mockito.verify(asyncClient, org.mockito.Mockito.never()).deleteMessage(org.mockito.ArgumentMatchers.argThat(
                    (DeleteMessageRequest request) -> "bad-handle".equals(request.receiptHandle())));
            org.junit.jupiter.api.Assertions.assertFalse(captured.list.isEmpty());
            captured.list.forEach(entry -> {
                org.junit.jupiter.api.Assertions.assertFalse(entry.getFormattedMessage().contains("private payload"));
                org.junit.jupiter.api.Assertions.assertNull(entry.getThrowableProxy());
            });
        } finally {
            listener.close(); logger.detachAppender(captured); captured.stop();
        }
    }
}
