package com.ticketing.orders.api;

import com.ticketing.orders.api.utils.HttpConstants;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration("routerRest")
public class Routes {

    @Bean
    public RouterFunction<ServerResponse> routerFunction(RequestHandler handler) {
        return RouterFunctions.route().POST(HttpConstants.ORDERS_PATH, handler::create)
            .GET(HttpConstants.ORDERS_PATH + HttpConstants.ORDER_ID_PATH, handler::find)
            .POST(HttpConstants.ORDERS_PATH + HttpConstants.CONFIRM_PATH, handler::confirm)
            .POST(HttpConstants.COMPLIMENTARY_ORDERS_PATH, handler::complimentary).build();
    }
}
