package com.ticketing.orders.api.config;

import com.ticketing.orders.api.utils.HttpConstants;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

@Component
public class SecurityHeadersConfig implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.set(HttpConstants.CONTENT_SECURITY_POLICY_HEADER, HttpConstants.CONTENT_SECURITY_POLICY);
        headers.set(HttpConstants.STRICT_TRANSPORT_SECURITY_HEADER, HttpConstants.STRICT_TRANSPORT_SECURITY);
        headers.set(HttpConstants.CONTENT_TYPE_OPTIONS_HEADER, HttpConstants.NO_SNIFF);
        headers.remove(HttpConstants.SERVER_HEADER);
        headers.set(HttpConstants.CACHE_CONTROL_HEADER, HttpConstants.NO_STORE);
        headers.set(HttpConstants.PRAGMA_HEADER, HttpConstants.NO_CACHE);
        headers.set(HttpConstants.REFERRER_POLICY_HEADER, HttpConstants.REFERRER_POLICY);
        return chain.filter(exchange);
    }
}
