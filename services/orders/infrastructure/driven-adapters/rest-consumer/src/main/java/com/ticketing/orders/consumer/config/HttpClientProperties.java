package com.ticketing.orders.consumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("adapter.rest-consumer")
public record HttpClientProperties(String baseUrl, Duration transportTimeout, Duration requestTimeout,
    int minPoolSize, Duration maxIdleTime, Duration maxLifeTime, Duration evictionInterval,
    Duration dnsCacheTtl) {
}
