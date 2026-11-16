package com.ticketing.orders.api.utils;

public final class HttpConstants {
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String INVALID_BODY = "INVALID_BODY";
    public static final String ORDER_ID_VARIABLE = "id";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String IDENTITY_ATTRIBUTE = "identity";
    public static final String ADMIN_REQUIRED = "ADMIN_REQUIRED";
    public static final String ORDERS_PATH = "/api/v1/orders";
    public static final String CONFIRM_PATH = "/{id}/confirm";
    public static final String COMPLIMENTARY_ORDERS_PATH = "/api/v1/admin/complimentary-orders";
    public static final String ORDER_ID_PATH = "/{id}";
    public static final String LOCAL = "local";
    public static final String PUBLIC_API_PREFIX = "/api/";
    public static final String GROUPS_HEADER = "X-Authenticated-Groups";
    public static final String SUBJECT_HEADER = "X-Authenticated-Subject";
    public static final String TRUSTED_GATEWAY = "trusted-gateway";
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";
    public static final String PROBLEM_STATUS = "status";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String PROBLEM_TITLE = "title";
    public static final String HTTP_ERROR = "HTTP_ERROR";
    public static final String TRACE_ID = "traceId";
    public static final String PROBLEM_TYPE = "type";
    public static final String PROBLEM_CODE = "code";
    public static final String PROBLEM_TYPE_PREFIX = "urn:ticketing:problem:";

    public static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'self'; form-action 'self'";
    public static final String STRICT_TRANSPORT_SECURITY_HEADER = "Strict-Transport-Security";
    public static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains; preload";
    public static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    public static final String NO_SNIFF = "nosniff";
    public static final String SERVER_HEADER = "Server";
    public static final String CACHE_CONTROL_HEADER = "Cache-Control";
    public static final String NO_STORE = "no-store";
    public static final String PRAGMA_HEADER = "Pragma";
    public static final String NO_CACHE = "no-cache";
    public static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    public static final String REFERRER_POLICY = "strict-origin-when-cross-origin";
    public static final String HEALTH_PATH = "/actuator/health";
    public static final String HEALTH_PROBE_PREFIX = "/actuator/health/";
    public static final String PROMETHEUS_PATH = "/actuator/prometheus";
    public static final String ALL_PATHS = "/**";

    private HttpConstants() {
    }
}
