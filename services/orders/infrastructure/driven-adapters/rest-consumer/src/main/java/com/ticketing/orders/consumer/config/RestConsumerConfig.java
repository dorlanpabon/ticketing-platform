package com.ticketing.orders.consumer.config;

import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
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

import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Configuration
public class RestConsumerConfig {

    private final String url;

    private final int timeout;

    public RestConsumerConfig(@Value("${INVENTORY_BASE_URL:http://localhost:8082}") String url,
                              @Value("${INVENTORY_HTTP_TIMEOUT_MS:2000}") int timeout) {
        this.url = url;
        this.timeout = timeout;
    }

    @Bean
    public WebClient getWebClient(WebClient.Builder builder, ConnectionProvider inventoryConnectionProvider) {
        return builder
            .baseUrl(url)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .clientConnector(getClientHttpConnector(inventoryConnectionProvider))
            .build();
    }

    @Bean(destroyMethod = "dispose")
    public ConnectionProvider inventoryConnectionProvider() {
        return ConnectionProvider.builder("inventory-http")
                .maxConnections(Math.max(ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS, 500))
                .maxIdleTime(Duration.ofSeconds(15))
                .maxLifeTime(Duration.ofSeconds(30))
                .evictInBackground(Duration.ofSeconds(15))
                .build();
    }

    private ClientHttpConnector getClientHttpConnector(ConnectionProvider connectionProvider) {
        /*
        IF YO REQUIRE APPEND SSL CERTIFICATE SELF SIGNED: this should be in the default cacerts trustore
        */
        return new ReactorClientHttpConnector(HttpClient.create(connectionProvider)
                .resolver(resolver -> resolver.cacheMaxTimeToLive(Duration.ofSeconds(30))
                        .cacheMinTimeToLive(Duration.ZERO)
                        .cacheNegativeTimeToLive(Duration.ZERO))
                .compress(true)
                .keepAlive(true)
                .option(CONNECT_TIMEOUT_MILLIS, timeout)
                .doOnConnected(connection -> {
                    connection.addHandlerLast(new ReadTimeoutHandler(timeout, MILLISECONDS));
                    connection.addHandlerLast(new WriteTimeoutHandler(timeout, MILLISECONDS));
                }));
    }

}
