package com.ticketing.catalog.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.*;
@Configuration
public class RouterRest {
    @Bean public RouterFunction<ServerResponse> routerFunction(Handler handler) {
        return RouterFunctions.route().POST("/api/v1/events",handler::create).GET("/api/v1/events",handler::list).GET("/api/v1/events/{id}",handler::find).build();
    }
}
