package com.ticketing.orders.consumer.utils;

public final class HttpClientConstants {
    public static final String RESERVATION_PATH = "/internal/reservations/{id}";
    public static final String INVENTORY_UNAVAILABLE = "INVENTORY_UNAVAILABLE";
    public static final String CONNECTION_POOL_NAME = "inventory-http";

    private HttpClientConstants() {
    }
}
