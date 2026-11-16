package com.ticketing.inventory.api.utils;

public final class HttpConstants {
    public static final String INVALID_BODY = "INVALID_BODY";
    public static final String ORDER_ID = "orderId";
    public static final String EVENT_ID = "eventId";
    public static final String LOCAL_AUTH_MODE = "local";
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String RESERVATION_PATH = "/internal/reservations/{orderId}";
    public static final String CONFIRMATION_PATH = "/internal/reservations/{orderId}/confirm";
    public static final String TICKETS_PATH = "/api/v1/events/{eventId}/tickets";
    public static final String AVAILABILITY_PATH = "/api/v1/events/{eventId}/availability";
    public static final String PAGE_LIMIT = "limit";
    public static final String INVALID_PAGE_SIZE = "INVALID_PAGE_SIZE";
    public static final String CURSOR = "cursor";
    public static final String LOCAL_CUSTOMER_ONE_TOKEN = "Bearer local-customer-1";
    public static final String LOCAL_CUSTOMER_TWO_TOKEN = "Bearer local-customer-2";
    public static final String LOCAL_ADMIN_TOKEN = "Bearer local-admin";
    public static final String AUTHENTICATED_SUBJECT_HEADER = "X-Authenticated-Subject";
    public static final String TRUSTED_GATEWAY_AUTH_MODE = "trusted-gateway";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";
    public static final String DEPENDENCY_UNAVAILABLE = "DEPENDENCY_UNAVAILABLE";
    public static final String PROBLEM_TYPE = "type";
    public static final String BLANK_PROBLEM_TYPE = "about:blank";
    public static final String PROBLEM_TITLE = "title";
    public static final String PROBLEM_CODE = "code";
    public static final String PROBLEM_STATUS = "status";
    public static final String TRACE_ID = "traceId";
    public static final String POST_METHOD = "POST";
    public static final String GET_METHOD = "GET";
    public static final String PUT_METHOD = "PUT";
    public static final String ALL_PATHS = "/**";
    public static final String HEALTH_PATH = "/actuator/health";
    public static final String HEALTH_PATH_PREFIX = "/actuator/health/";
    public static final String PROMETHEUS_PATH = "/actuator/prometheus";
    public static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'self'; form-action 'self'";
    public static final String STRICT_TRANSPORT_SECURITY_HEADER = "Strict-Transport-Security";
    public static final String STRICT_TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains; preload";
    public static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    public static final String NOSNIFF = "nosniff";
    public static final String SERVER_HEADER = "Server";
    public static final String CACHE_CONTROL_HEADER = "Cache-Control";
    public static final String NO_STORE = "no-store";
    public static final String PRAGMA_HEADER = "Pragma";
    public static final String NO_CACHE = "no-cache";
    public static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    public static final String REFERRER_POLICY = "strict-origin-when-cross-origin";

    private HttpConstants() {
    }
}
