package com.ticketing.catalog.api;

import com.ticketing.catalog.model.CatalogErrorCodes;
import com.ticketing.catalog.model.DomainFailure;

import org.springframework.web.reactive.function.server.ServerRequest;

public record Identity(String subject, boolean admin) {
    public static Identity from(ServerRequest request) {
        return (Identity) request.attribute(HttpConstants.IDENTITY_ATTRIBUTE)
            .orElseThrow(() -> new DomainFailure(401, CatalogErrorCodes.UNAUTHENTICATED));
    }

    public void requireAdmin() {
        if (!admin)
            throw new DomainFailure(403, CatalogErrorCodes.ADMIN_REQUIRED);
    }
}
