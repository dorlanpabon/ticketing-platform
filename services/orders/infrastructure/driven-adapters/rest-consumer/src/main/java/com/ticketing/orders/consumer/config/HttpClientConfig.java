package com.ticketing.orders.consumer.config;

import static com.ticketing.orders.consumer.utils.HttpClientConstants.CONNECTION_POOL_NAME;

import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration("restConsumerConfig")
@EnableConfigurationProperties(HttpClientProperties.class)
public class HttpClientConfig {
    private final HttpClientProperties properties;

    public HttpClientConfig(HttpClientProperties properties) {
        this.properties = properties;
    }

    @Bean
    public WebClient getWebClient(WebClient.Builder builder, ConnectionProvider inventoryConnectionProvider) {
        return builder.baseUrl(properties.baseUrl())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .clientConnector(getClientHttpConnector(inventoryConnectionProvider)).build();
    }

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider inventoryConnectionProvider() {
        return ConnectionProvider.builder(CONNECTION_POOL_NAME)
            .maxConnections(
                Math.max(ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS, properties.minPoolSize()))
            .maxIdleTime(properties.maxIdleTime()).maxLifeTime(properties.maxLifeTime())
            .evictInBackground(properties.evictionInterval()).build();
    }

    private ClientHttpConnector getClientHttpConnector(ConnectionProvider connectionProvider) {
        int timeoutMillis = Math.toIntExact(properties.transportTimeout().toMillis());
        return new ReactorClientHttpConnector(HttpClient.create(connectionProvider)
            .resolver(resolver -> resolver.cacheMaxTimeToLive(properties.dnsCacheTtl())
                .cacheMinTimeToLive(Duration.ZERO).cacheNegativeTimeToLive(Duration.ZERO))
            .compress(true).keepAlive(true).option(CONNECT_TIMEOUT_MILLIS, timeoutMillis)
            .doOnConnected(connection -> {
                connection.addHandlerLast(new ReadTimeoutHandler(timeoutMillis, MILLISECONDS));
                connection.addHandlerLast(new WriteTimeoutHandler(timeoutMillis, MILLISECONDS));
            }));
    }
}
