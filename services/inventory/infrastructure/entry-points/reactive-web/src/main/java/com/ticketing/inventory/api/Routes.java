package com.ticketing.inventory.api;

import com.ticketing.inventory.api.utils.HttpConstants;
import com.ticketing.inventory.model.InventoryException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;

import java.util.Map;

@Configuration
public class Routes {

    @Bean
    public RouterFunction<ServerResponse> routerFunction(RequestHandler handler) {
        return RouterFunctions.route().PUT(HttpConstants.RESERVATION_PATH, handler::reserve)
            .GET(HttpConstants.RESERVATION_PATH, handler::get)
            .POST(HttpConstants.CONFIRMATION_PATH, handler::confirm)
            .GET(HttpConstants.TICKETS_PATH, handler::tickets)
            .GET(HttpConstants.AVAILABILITY_PATH, handler::availability)
            .onError(InventoryException.class,
                (error, request) -> ServerResponse.status(error.status())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyValue(problem(request, error.code(), error.status())))
            .onError(DataBufferLimitException.class,
                (error, request) -> ServerResponse.status(413).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyValue(problem(request, HttpConstants.BODY_TOO_LARGE, 413)))
            .onError(ServerWebInputException.class,
                (error, request) -> ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyValue(problem(request, HttpConstants.INVALID_BODY, 400)))
            .onError(Exception.class,
                (error, request) -> ServerResponse.status(503).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyValue(problem(request, HttpConstants.DEPENDENCY_UNAVAILABLE, 503)))
            .build();
    }

    private static Map<String, Object> problem(ServerRequest request, String code, int status) {
        return Map.of(HttpConstants.PROBLEM_TYPE, HttpConstants.BLANK_PROBLEM_TYPE,
            HttpConstants.PROBLEM_TITLE, code, HttpConstants.PROBLEM_CODE, code, HttpConstants.PROBLEM_STATUS,
            status, HttpConstants.TRACE_ID, request.exchange().getRequest().getId());
    }
}
