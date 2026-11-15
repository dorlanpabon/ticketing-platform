package com.ticketing.catalog.api.config;

import com.ticketing.catalog.api.Identity;
import com.ticketing.catalog.model.DomainFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.util.Arrays;
import java.util.Set;
@Component
@Order(-100)
public final class IdentityFilter implements WebFilter {
    private static final String LOCAL_MODE = "local";
    private final String mode;
    private final boolean local;
    public IdentityFilter(@Value("${AUTH_MODE:trusted-gateway}") String mode,@Value("${spring.profiles.active:aws}") String profile) {
        this.mode=mode; this.local=Arrays.asList(profile.split(",")).contains(LOCAL_MODE);
        if(mode.equals(LOCAL_MODE) && !local) throw new IllegalStateException("Local authentication requires the local profile");
        if(!Set.of(LOCAL_MODE,"trusted-gateway").contains(mode)) throw new IllegalStateException("Unsupported authentication mode");
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        if(!exchange.getRequest().getPath().value().startsWith("/api/")) return chain.filter(exchange);
        return Mono.defer(() -> {
            String subject; boolean admin;
            if(local && mode.equals(LOCAL_MODE)) {
                String bearer=exchange.getRequest().getHeaders().getFirst("Authorization");
                if(bearer==null || !Set.of("Bearer local-customer-1","Bearer local-customer-2","Bearer local-admin").contains(bearer)) return Mono.error(new DomainFailure(401,"UNAUTHENTICATED"));
                subject=bearer.substring(7); admin=subject.equals("local-admin");
            } else {
                subject=exchange.getRequest().getHeaders().getFirst("X-Authenticated-Subject");
                String groups=exchange.getRequest().getHeaders().getFirst("X-Authenticated-Groups");
                admin=groups!=null && Arrays.stream(groups.replace("[","").replace("]","").replace("\"","").split(",")).map(String::trim).anyMatch("admin"::equals);
                if(subject==null || subject.isBlank() || subject.length()>128) return Mono.error(new DomainFailure(401,"UNAUTHENTICATED"));
            }
            exchange.getAttributes().put("identity",new Identity(subject,admin));
            return chain.filter(exchange);
        });
    }
}
