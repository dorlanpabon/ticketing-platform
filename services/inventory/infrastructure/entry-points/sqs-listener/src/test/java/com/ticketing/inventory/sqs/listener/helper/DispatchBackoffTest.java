package com.ticketing.inventory.sqs.listener.helper;

import com.ticketing.inventory.model.DispatchBatch;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatchBackoffTest {
    @Test void idleIsBoundedActivityResetsAndFollowerUsesRemainingLease() {
        var backoff = new DispatchBackoff(() -> 100);
        for (long expected : new long[]{600, 1100, 2100, 4100, 8100, 10000, 10000}) {
            assertEquals(Duration.ofMillis(expected), backoff.next(new DispatchBatch(0, 0)));
        }
        assertEquals(Duration.ofSeconds(7), backoff.next(new DispatchBatch(0, 7000)));
        assertEquals(Duration.ofMillis(500), backoff.next(new DispatchBatch(1, 0)));
        assertEquals(Duration.ofMillis(600), backoff.next(new DispatchBatch(0, 0)));
    }
}
