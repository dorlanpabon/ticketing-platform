package com.ticketing.catalog.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Keeps consumer replicas off the business HTTP surface while retaining probes. */
@Component
@Order(-110)
public final class RuntimeRoleFilter implements WebFilter {
    private final boolean apiEnabled;

    public RuntimeRoleFilter(@Value("${API_ENABLED:true}") boolean apiEnabled) {
        this.apiEnabled = apiEnabled;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!apiEnabled && !managementPath(path)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean managementPath(String path) {
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/actuator/prometheus");
    }
}
