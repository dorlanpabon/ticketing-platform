package com.ticketing.catalog.usecase;

import java.time.Instant;

record ValidatedEventDetails(String name, String venue, Instant startsAt, int capacity) {
}
