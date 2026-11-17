package com.ticketing.orders.model;

import com.ticketing.orders.model.utils.ErrorCodes;
import com.ticketing.orders.model.utils.ValidationRules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Validation {
    private Validation() {
    }

    public static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new DomainFailure(400, ErrorCodes.INVALID_INPUT);
        return value.trim();
    }

    public static String key(String value) {
        if (value == null || !value.matches(ValidationRules.IDEMPOTENCY_KEY_PATTERN))
            throw new DomainFailure(400, ErrorCodes.INVALID_IDEMPOTENCY_KEY);
        return value;
    }

    public static String identifier(String value) {
        if (value == null || !value.matches(ValidationRules.IDENTIFIER_PATTERN))
            throw new DomainFailure(400, ErrorCodes.INVALID_IDENTIFIER);
        return value;
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ValidationRules.HASH_ALGORITHM)
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(ValidationRules.HASH_UNAVAILABLE, error);
        }
    }
}
