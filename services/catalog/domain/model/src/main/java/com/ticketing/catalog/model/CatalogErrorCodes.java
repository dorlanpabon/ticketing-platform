package com.ticketing.catalog.model;

public final class CatalogErrorCodes {
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String INVALID_IDEMPOTENCY_KEY = "INVALID_IDEMPOTENCY_KEY";
    public static final String INVALID_EVENT = "INVALID_EVENT";
    public static final String EVENT_NOT_FOUND = "EVENT_NOT_FOUND";
    public static final String INVALID_PAGE_SIZE = "INVALID_PAGE_SIZE";
    public static final String UNSUPPORTED_MESSAGE = "UNSUPPORTED_MESSAGE";
    public static final String INVALID_MESSAGE = "INVALID_MESSAGE";
    public static final String INVALID_CURSOR = "INVALID_CURSOR";
    public static final String UNKNOWN_EVENT_MESSAGE = "UNKNOWN_EVENT_MESSAGE";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String INVALID_BODY = "INVALID_BODY";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String ADMIN_REQUIRED = "ADMIN_REQUIRED";
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";

    private CatalogErrorCodes() {
    }
}
