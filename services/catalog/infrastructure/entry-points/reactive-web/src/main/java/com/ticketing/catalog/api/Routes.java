package com.ticketing.catalog.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class Routes {
    @Bean
    public RouterFunction<ServerResponse> routerFunction(RequestHandler handler) {
        return RouterFunctions.route().POST(HttpConstants.EVENTS_PATH, handler::create)
            .GET(HttpConstants.EVENTS_PATH, handler::list).GET(HttpConstants.EVENT_PATH, handler::find)
            .build();
    }
}
