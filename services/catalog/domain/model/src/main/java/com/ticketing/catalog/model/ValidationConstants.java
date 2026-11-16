package com.ticketing.catalog.model;

final class ValidationConstants {
    static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9_.:-]{8,128}";
    static final String HASH_ALGORITHM = "SHA-256";
    static final String HASH_ALGORITHM_UNAVAILABLE = "SHA-256 unavailable";

    private ValidationConstants() {
    }
}
