package com.ticketing.catalog.metrics.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricRecord;
import software.amazon.awssdk.metrics.SdkMetric;
import software.amazon.awssdk.metrics.internal.EmptyMetricCollection;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

class MicrometerMetricPublisherTest {

    @Test
    void metricTestWithEmptyCollection() {
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        MicrometerMetricPublisher micrometerMetricPublisher = new MicrometerMetricPublisher(meterRegistry);

        micrometerMetricPublisher.publish(EmptyMetricCollection.create());
        micrometerMetricPublisher.close();

        assertNotNull(micrometerMetricPublisher);
    }

    @Test
    void metricTestPublishesDuration() {
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        Timer timer = mock(Timer.class);
        when(meterRegistry.timer(any(), anyTagList())).thenReturn(timer);

        MetricRecord<Duration> durationRecord = metricRecord("ApiCallDuration", Duration.ofMillis(150));
        MetricCollection metricCollection = metricCollectionOf(durationRecord);

        new MicrometerMetricPublisher(meterRegistry).publish(metricCollection);

        verify(meterRegistry).timer("ApiCallDuration", List.<Tag>of());
        verify(timer).record(Duration.ofMillis(150));
    }

    @Test
    void metricTestPublishesCounter() {
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        Counter counter = mock(Counter.class);
        when(meterRegistry.counter(any(), anyTagList())).thenReturn(counter);

        MetricRecord<Integer> integerRecord = metricRecord("RetryCount", 3);
        MetricCollection metricCollection = metricCollectionOf(integerRecord);

        new MicrometerMetricPublisher(meterRegistry).publish(metricCollection);

        verify(meterRegistry).counter("RetryCount", List.<Tag>of());
        verify(counter).increment(3);
    }

    @Test
    void metricTestBuildsTagsFromStringAndBooleanRecords() {
        MeterRegistry meterRegistry = mock(MeterRegistry.class);
        Timer timer = mock(Timer.class);
        when(meterRegistry.timer(any(), anyTagList())).thenReturn(timer);

        MetricRecord<String> stringRecord = metricRecord("ServiceId", "my-service");
        MetricRecord<Boolean> booleanRecord = metricRecord("ApiCallSuccessful", true);
        MetricRecord<Duration> durationRecord = metricRecord("ApiCallDuration", Duration.ofMillis(10));
        MetricCollection metricCollection = metricCollectionOf(stringRecord, booleanRecord, durationRecord);

        new MicrometerMetricPublisher(meterRegistry).publish(metricCollection);

        ArgumentCaptor<List<Tag>> tagsCaptor = ArgumentCaptor.forClass(List.class);
        verify(meterRegistry).timer(eq("ApiCallDuration"), tagsCaptor.capture());
        List<Tag> tags = tagsCaptor.getValue();
        assertTrue(tags.contains(Tag.of("ServiceId", "my-service")));
        assertTrue(tags.contains(Tag.of("ApiCallSuccessful", "true")));
    }

    @Test
    void publishesNestedHttpAttemptsWithInheritedContextAndBoundedLabels() {
        var registry = new SimpleMeterRegistry();
        try {
            MetricCollection http = metricCollectionOf(metricRecord("HttpClientName", "NettyNio"),
                metricRecord("ConcurrencyAcquireDuration", Duration.ofMillis(75)),
                metricRecord("AwsRequestId", "request-specific-id"),
                metricRecord("Endpoint", "https://sensitive.example/resource"));
            MetricCollection attempt = metricCollectionOf(
                metricRecord("ServiceCallDuration", Duration.ofMillis(150)),
                metricRecord("HttpStatusCode", 200));
            when(attempt.children()).thenReturn(List.of(http));
            MetricCollection root = metricCollectionOf(metricRecord("ServiceId", "DynamoDB"),
                metricRecord("OperationName", "GetItem"), metricRecord("ApiCallSuccessful", true),
                metricRecord("ApiCallDuration", Duration.ofMillis(225)), metricRecord("RetryCount", 1));
            when(root.children()).thenReturn(List.of(attempt));

            new MicrometerMetricPublisher(registry).publish(root);

            var acquire = registry.get("ConcurrencyAcquireDuration").tags("ServiceId", "DynamoDB",
                "OperationName", "GetItem", "ApiCallSuccessful", "true", "HttpClientName", "NettyNio")
                .timer();
            assertEquals(1, acquire.count());
            assertEquals(75, acquire.totalTime(TimeUnit.MILLISECONDS));
            assertEquals(1, registry.get("ServiceCallDuration")
                .tags("ServiceId", "DynamoDB", "OperationName", "GetItem").timer().count());
            assertEquals(1, registry.get("ApiCallDuration").timer().count());
            assertEquals(1, registry.get("RetryCount").counter().count());
            assertNull(registry.find("HttpStatusCode").counter());
            assertTrue(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> List.of("AwsRequestId", "Endpoint").contains(tag.getKey())));
        } finally {
            registry.close();
        }
    }

    @Test
    void concurrencyValuesArePerAttemptSamplesNotAccumulatingCounters() {
        var registry = new SimpleMeterRegistry();
        try {
            MetricCollection first = metricCollectionOf(metricRecord("HttpClientName", "NettyNio"),
                metricRecord("MaxConcurrency", 50), metricRecord("AvailableConcurrency", 40),
                metricRecord("LeasedConcurrency", 10), metricRecord("PendingConcurrencyAcquires", 7));
            MetricCollection second = metricCollectionOf(metricRecord("HttpClientName", "NettyNio"),
                metricRecord("MaxConcurrency", 50), metricRecord("AvailableConcurrency", 45),
                metricRecord("LeasedConcurrency", 5), metricRecord("PendingConcurrencyAcquires", 2));
            MetricCollection root = metricCollectionOf(metricRecord("ServiceId", "DynamoDB"),
                metricRecord("OperationName", "TransactWriteItems"));
            when(root.children()).thenReturn(List.of(first, second));

            new MicrometerMetricPublisher(registry).publish(root);

            var pending = registry.get("PendingConcurrencyAcquires").tags("ServiceId", "DynamoDB",
                "OperationName", "TransactWriteItems", "HttpClientName", "NettyNio").summary();
            assertEquals(2, pending.count());
            assertEquals(9, pending.totalAmount());
            assertEquals(7, pending.max());
            assertEquals(2, registry.get("MaxConcurrency").summary().count());
            assertEquals(100, registry.get("MaxConcurrency").summary().totalAmount());
            assertEquals(15, registry.get("LeasedConcurrency").summary().totalAmount());
            assertEquals(85, registry.get("AvailableConcurrency").summary().totalAmount());
            assertNull(registry.find("PendingConcurrencyAcquires").counter());
        } finally {
            registry.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> MetricRecord<T> metricRecord(String name, T value) {
        MetricRecord<T> record = mock(MetricRecord.class);
        SdkMetric<T> metric = mock(SdkMetric.class);
        when(metric.name()).thenReturn(name);
        when(record.metric()).thenReturn(metric);
        when(record.value()).thenReturn(value);
        return record;
    }

    private static MetricCollection metricCollectionOf(MetricRecord<?>... records) {
        MetricCollection metricCollection = mock(MetricCollection.class);
        when(metricCollection.stream()).thenAnswer(invocation -> List.of(records).stream());
        when(metricCollection.children()).thenReturn(List.of());
        return metricCollection;
    }

    @SuppressWarnings("unchecked")
    private static List<Tag> anyTagList() {
        return any(List.class);
    }
}
