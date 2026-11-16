package com.ticketing.orders.api.config;

import com.ticketing.orders.api.utils.HttpConstants;

import com.ticketing.orders.api.Identity;
import com.ticketing.orders.model.DomainFailure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Set;

@Component
@Order(-100)
public final class IdentityFilter implements WebFilter {
    private final String mode;
    private final boolean localProfileActive;

    public IdentityFilter(@Value("${AUTH_MODE:trusted-gateway}") String mode,
        @Value("${spring.profiles.active:}") String profile) {
        this.mode = mode;
        this.localProfileActive = Arrays.asList(profile.split(",")).contains(HttpConstants.LOCAL);
        if (mode.equals(HttpConstants.LOCAL) && !localProfileActive)
            throw new IllegalStateException("Local authentication requires the local profile");
        if (!Set.of(HttpConstants.LOCAL, HttpConstants.TRUSTED_GATEWAY).contains(mode))
            throw new IllegalStateException("Unsupported authentication mode");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(HttpConstants.PUBLIC_API_PREFIX))
            return chain.filter(exchange);
        return Mono.defer(() -> {
            String subject;
            boolean admin;
            if (localProfileActive && mode.equals(HttpConstants.LOCAL)) {
                String bearer = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if (bearer == null
                    || !Set.of("Bearer local-customer-1", "Bearer local-customer-2", "Bearer local-admin")
                        .contains(bearer))
                    return Mono.error(new DomainFailure(401, HttpConstants.UNAUTHENTICATED));
                subject = bearer.substring(7);
                admin = subject.equals("local-admin");
            } else {
                subject = exchange.getRequest().getHeaders().getFirst(HttpConstants.SUBJECT_HEADER);
                String groups = exchange.getRequest().getHeaders().getFirst(HttpConstants.GROUPS_HEADER);
                admin = groups != null
                    && Arrays.stream(groups.replace("[", "").replace("]", "").replace("\"", "").split(","))
                        .map(String::trim).anyMatch("admin"::equals);
                if (subject == null || subject.isBlank() || subject.length() > 128)
                    return Mono.error(new DomainFailure(401, HttpConstants.UNAUTHENTICATED));
            }
            exchange.getAttributes().put(HttpConstants.IDENTITY_ATTRIBUTE, new Identity(subject, admin));
            return chain.filter(exchange);
        });
    }
}
