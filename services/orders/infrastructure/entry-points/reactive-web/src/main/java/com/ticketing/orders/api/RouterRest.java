package com.ticketing.orders.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.*;
@Configuration
public class RouterRest {
    @Bean public RouterFunction<ServerResponse> routerFunction(Handler handler) {
        return RouterFunctions.route().POST("/api/v1/orders",handler::create).GET("/api/v1/orders/{id}",handler::find)
                .POST("/api/v1/orders/{id}/confirm",handler::confirm).POST("/api/v1/admin/complimentary-orders",handler::complimentary).build();
    }
}
