package com.ticketing.catalog.api.config;

import com.ticketing.catalog.api.HttpConstants;
import com.ticketing.catalog.api.Identity;
import com.ticketing.catalog.model.CatalogErrorCodes;
import com.ticketing.catalog.model.DomainFailure;

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
    private final boolean local;

    public IdentityFilter(@Value("${AUTH_MODE:trusted-gateway}") String mode,
        @Value("${spring.profiles.active:aws}") String profile) {
        this.mode = mode;
        this.local = Arrays.asList(profile.split(",")).contains(HttpConstants.LOCAL_AUTH_MODE);
        if (mode.equals(HttpConstants.LOCAL_AUTH_MODE) && !local)
            throw new IllegalStateException("Local authentication requires the local profile");
        if (!Set.of(HttpConstants.LOCAL_AUTH_MODE, HttpConstants.GATEWAY_AUTH_MODE).contains(mode))
            throw new IllegalStateException("Unsupported authentication mode");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(HttpConstants.API_PREFIX))
            return chain.filter(exchange);
        return Mono.defer(() -> {
            String subject;
            boolean admin;
            if (local && mode.equals(HttpConstants.LOCAL_AUTH_MODE)) {
                String bearer = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                if (bearer == null || !Set.of(HttpConstants.LOCAL_CUSTOMER_ONE_BEARER,
                    HttpConstants.LOCAL_CUSTOMER_TWO_BEARER, HttpConstants.LOCAL_ADMIN_BEARER)
                    .contains(bearer))
                    return Mono.error(new DomainFailure(401, CatalogErrorCodes.UNAUTHENTICATED));
                subject = bearer.substring(7);
                admin = subject.equals(HttpConstants.LOCAL_ADMIN_SUBJECT);
            } else {
                subject = exchange.getRequest().getHeaders().getFirst(HttpConstants.SUBJECT_HEADER);
                String groups = exchange.getRequest().getHeaders().getFirst(HttpConstants.GROUPS_HEADER);
                admin = groups != null
                    && Arrays.stream(groups.replace("[", "").replace("]", "").replace("\"", "").split(","))
                        .map(String::trim).anyMatch(HttpConstants.ADMIN_GROUP::equals);
                if (subject == null || subject.isBlank() || subject.length() > 128)
                    return Mono.error(new DomainFailure(401, CatalogErrorCodes.UNAUTHENTICATED));
            }
            exchange.getAttributes().put(HttpConstants.IDENTITY_ATTRIBUTE, new Identity(subject, admin));
            return chain.filter(exchange);
        });
    }
}
