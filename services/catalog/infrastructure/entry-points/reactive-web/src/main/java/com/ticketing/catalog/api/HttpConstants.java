package com.ticketing.catalog.api;

public final class HttpConstants {
    public static final String API_PREFIX = "/api/";
    public static final String EVENTS_PATH = "/api/v1/events";
    public static final String EVENT_PATH = EVENTS_PATH + "/{id}";
    public static final String EVENT_LOCATION_PREFIX = EVENTS_PATH + "/";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String SUBJECT_HEADER = "X-Authenticated-Subject";
    public static final String GROUPS_HEADER = "X-Authenticated-Groups";
    public static final String IDENTITY_ATTRIBUTE = "identity";
    public static final String HEALTH_PATH = "/actuator/health";
    public static final String HEALTH_PATH_PREFIX = HEALTH_PATH + "/";
    public static final String PROMETHEUS_PATH = "/actuator/prometheus";
    public static final String ALL_PATHS = "/**";
    public static final String GET_METHOD = "GET";
    public static final String POST_METHOD = "POST";
    public static final String LOCAL_AUTH_MODE = "local";
    public static final String GATEWAY_AUTH_MODE = "trusted-gateway";
    public static final String ADMIN_GROUP = "admin";
    public static final String LOCAL_ADMIN_SUBJECT = "local-admin";
    public static final String LOCAL_CUSTOMER_ONE_BEARER = "Bearer local-customer-1";
    public static final String LOCAL_CUSTOMER_TWO_BEARER = "Bearer local-customer-2";
    public static final String LOCAL_ADMIN_BEARER = "Bearer local-admin";
    public static final String PROBLEM_TYPE_KEY = "type";
    public static final String PROBLEM_TYPE_PREFIX = "urn:ticketing:problem:";
    public static final String PROBLEM_TITLE_KEY = "title";
    public static final String PROBLEM_STATUS_KEY = "status";
    public static final String PROBLEM_CODE_KEY = "code";
    public static final String HTTP_ERROR = "HTTP_ERROR";
    public static final String TRACE_ID_KEY = "traceId";
    public static final String CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy";
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; frame-ancestors 'self'; form-action 'self'";
    public static final String TRANSPORT_SECURITY_HEADER = "Strict-Transport-Security";
    public static final String TRANSPORT_SECURITY = "max-age=31536000; includeSubDomains; preload";
    public static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    public static final String NO_SNIFF = "nosniff";
    public static final String NO_STORE = "no-store";
    public static final String NO_CACHE = "no-cache";
    public static final String REFERRER_POLICY_HEADER = "Referrer-Policy";
    public static final String REFERRER_POLICY = "strict-origin-when-cross-origin";
    static final String EVENT_ID_PATH_VARIABLE = "id";
    static final String LIMIT_QUERY_PARAMETER = "limit";
    static final String CURSOR_QUERY_PARAMETER = "cursor";

    private HttpConstants() {
    }
}
