package com.ticketing.inventory.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import com.ticketing.inventory.model.InventoryException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunctions;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class RouterRest {
    @Bean
    public RouterFunction<ServerResponse> routerFunction(Handler handler) {
        return RouterFunctions.route()
                .PUT("/internal/reservations/{orderId}", handler::reserve)
                .GET("/internal/reservations/{orderId}", handler::get)
                .POST("/internal/reservations/{orderId}/confirm", handler::confirm)
                .GET("/api/v1/events/{eventId}/tickets", handler::tickets)
                .GET("/api/v1/events/{eventId}/availability", handler::availability)
                .onError(InventoryException.class, (error, request) -> ServerResponse.status(error.status())
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(problem(error.code(), error.status())))
                .onError(org.springframework.core.io.buffer.DataBufferLimitException.class, (error, request) -> ServerResponse.status(413)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(problem("BODY_TOO_LARGE", 413)))
                .onError(org.springframework.web.server.ServerWebInputException.class, (error, request) -> ServerResponse.badRequest()
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(problem("INVALID_BODY", 400)))
                .onError(Exception.class, (error, request) -> ServerResponse.status(503)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(problem("DEPENDENCY_UNAVAILABLE", 503)))
                .build();
    }

    private static Map<String, Object> problem(String code, int status) {
        return Map.of("type", "about:blank", "title", code, "code", code, "status", status, "traceId", UUID.randomUUID().toString());
    }
}
