package com.ticketing.inventory.usecase;

final class OutboxConstants {
    static final int MINIMUM_PUBLISH_CONCURRENCY = 1;
    static final long NO_PUBLICATIONS = 0L;
    static final long NO_DEFER_MILLIS = 0L;
    static final long MINIMUM_DEFER_MILLIS = 1L;
    static final String INVALID_PUBLISH_CONCURRENCY = "Outbox publish concurrency must be positive";
    static final String INVALID_DISPATCH_TIMEOUT = "Outbox dispatch timeout must be positive";

    private OutboxConstants() {
    }
}
