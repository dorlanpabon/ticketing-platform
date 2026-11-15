package com.ticketing.inventory.sqs.listener.helper;

import com.ticketing.inventory.model.DispatchBatch;
import java.time.Duration;
import java.util.function.LongSupplier;

final class DispatchBackoff {
    private final LongSupplier jitter;
    private long idleMillis = 250;

    DispatchBackoff() { this(() -> java.util.concurrent.ThreadLocalRandom.current().nextLong(251)); }
    DispatchBackoff(LongSupplier jitter) { this.jitter = jitter; }

    Duration next(DispatchBatch batch) {
        if (batch.deferMillis() > 0) return Duration.ofMillis(batch.deferMillis());
        if (batch.published() > 0) {
            idleMillis = 250;
            return Duration.ofMillis(500);
        }
        idleMillis = Math.min(10_000, idleMillis * 2);
        return Duration.ofMillis(Math.min(10_000, idleMillis + jitter.getAsLong()));
    }
}
