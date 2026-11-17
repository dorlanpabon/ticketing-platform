package com.ticketing.catalog.metrics.aws;

import java.util.Set;

final class MetricConstants {
    static final String RETRY_COUNT = "RetryCount";
    static final Set<String> TAG_KEYS = Set.of("ServiceId", "OperationName", "ApiCallSuccessful",
        "HttpClientName");
    static final Set<String> CONCURRENCY_SAMPLES = Set.of("MaxConcurrency", "AvailableConcurrency",
        "LeasedConcurrency", "PendingConcurrencyAcquires");

    private MetricConstants() {
    }
}
