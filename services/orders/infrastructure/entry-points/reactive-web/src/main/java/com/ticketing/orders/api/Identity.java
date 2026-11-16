package com.ticketing.orders.api;

import com.ticketing.orders.api.utils.HttpConstants;

import com.ticketing.orders.model.DomainFailure;

import org.springframework.web.reactive.function.server.ServerRequest;

public record Identity(String subject, boolean admin) {
    public static Identity from(ServerRequest request) {
        return (Identity) request.attribute(HttpConstants.IDENTITY_ATTRIBUTE)
            .orElseThrow(() -> new DomainFailure(401, HttpConstants.UNAUTHENTICATED));
    }

    public void requireAdmin() {
        if (!admin)
            throw new DomainFailure(403, HttpConstants.ADMIN_REQUIRED);
    }
}
