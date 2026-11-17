package com.ticketing.catalog.model;

import static com.ticketing.catalog.model.ValidationConstants.HASH_ALGORITHM;
import static com.ticketing.catalog.model.ValidationConstants.HASH_ALGORITHM_UNAVAILABLE;
import static com.ticketing.catalog.model.ValidationConstants.IDEMPOTENCY_KEY_PATTERN;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Validation {
    private Validation() {
    }

    public static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new DomainFailure(400, CatalogErrorCodes.INVALID_INPUT);
        }
        return value.trim();
    }

    public static String key(String value) {
        if (value == null || !value.matches(IDEMPOTENCY_KEY_PATTERN)) {
            throw new DomainFailure(400, CatalogErrorCodes.INVALID_IDEMPOTENCY_KEY);
        }
        return value;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(HASH_ALGORITHM_UNAVAILABLE, error);
        }
    }
}
