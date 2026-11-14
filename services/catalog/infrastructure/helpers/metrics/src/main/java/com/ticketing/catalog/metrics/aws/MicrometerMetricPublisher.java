package com.ticketing.catalog.metrics.aws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class MicrometerMetricPublisher implements MetricPublisher {
    private final MeterRegistry registry;

    public MicrometerMetricPublisher(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void publish(MetricCollection metricCollection) {
        List<Tag> tags = buildTags(metricCollection);
        metricCollection.stream()
                .filter(record -> record.value() instanceof Duration || record.value() instanceof Integer)
                .forEach(record -> {
                    if (record.value() instanceof Duration duration) {
                        registry.timer(record.metric().name(), tags).record(duration);
                    } else if (record.value() instanceof Integer count) {
                        registry.counter(record.metric().name(), tags).increment(count);
                    }
                });
    }

    @Override
    public void close() {
        // Micrometer owns the registry; this publisher allocates no threads or I/O resources.
    }

    private List<Tag> buildTags(MetricCollection metricCollection) {
        return metricCollection.stream()
                .filter(record -> record.value() instanceof String || record.value() instanceof Boolean)
                .map(record -> Tag.of(record.metric().name(), record.value().toString()))
                .collect(Collectors.toList());
    }
}
