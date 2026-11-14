package com.ticketing.catalog.api;

import com.ticketing.catalog.model.DomainFailure;
import org.springframework.web.reactive.function.server.ServerRequest;
public record Identity(String subject,boolean admin) {
    public static Identity from(ServerRequest request) { return (Identity)request.attribute("identity").orElseThrow(() -> new DomainFailure(401,"UNAUTHENTICATED")); }
    public void requireAdmin() { if(!admin) throw new DomainFailure(403,"ADMIN_REQUIRED"); }
}
