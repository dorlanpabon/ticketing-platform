package com.ticketing.orders.metrics.aws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

@Component
public class MicrometerMetricPublisher implements MetricPublisher {
    private static final String RETRY_COUNT = "RetryCount";
    private static final Set<String> TAG_KEYS = Set.of("ServiceId", "OperationName", "ApiCallSuccessful", "HttpClientName");
    private static final Set<String> CONCURRENCY_SAMPLES = Set.of("MaxConcurrency", "AvailableConcurrency",
            "LeasedConcurrency", "PendingConcurrencyAcquires");
    private final MeterRegistry registry;

    public MicrometerMetricPublisher(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void publish(MetricCollection metricCollection) {
        publish(metricCollection, List.of());
    }

    private void publish(MetricCollection metricCollection, List<Tag> inheritedTags) {
        List<Tag> tags = buildTags(metricCollection, inheritedTags);
        metricCollection.stream().forEach(record -> {
            String name = record.metric().name();
            if (record.value() instanceof Duration duration) {
                registry.timer(name, tags).record(duration);
            } else if (record.value() instanceof Integer value && value >= 0) {
                if (CONCURRENCY_SAMPLES.contains(name)) {
                    // SDK snapshots per attempt: a distribution, not a live gauge or additive counter.
                    registry.summary(name, tags).record(value);
                } else if (RETRY_COUNT.equals(name)) {
                    registry.counter(name, tags).increment(value);
                }
            }
        });
        metricCollection.children().forEach(child -> publish(child, tags));
    }

    @Override
    public void close() {

    }

    private List<Tag> buildTags(MetricCollection metricCollection, List<Tag> inheritedTags) {
        Map<String, String> labels = new LinkedHashMap<>();
        inheritedTags.forEach(tag -> labels.put(tag.getKey(), tag.getValue()));
        metricCollection.stream()
                .filter(record -> TAG_KEYS.contains(record.metric().name()))
                .filter(record -> record.value() instanceof String || record.value() instanceof Boolean)
                .forEach(record -> labels.put(record.metric().name(), record.value().toString()));
        return labels.entrySet().stream().map(entry -> Tag.of(entry.getKey(), entry.getValue())).toList();
    }
}
