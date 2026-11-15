package com.ticketing.catalog.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
public final class Validation {
    private Validation() { }
    public static String text(String value, int max) {
        if(value==null || value.isBlank() || value.length()>max) throw new DomainFailure(400,"INVALID_INPUT");
        return value.trim();
    }
    public static String key(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_.:-]{8,128}")) throw new DomainFailure(400,"INVALID_IDEMPOTENCY_KEY");
        return value;
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable",error); }
    }
}
