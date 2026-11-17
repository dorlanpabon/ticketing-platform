package com.ticketing.orders.api.config;

import com.ticketing.orders.api.utils.HttpConstants;

import com.ticketing.orders.model.DomainFailure;

import org.springframework.core.annotation.Order;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;

import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Component
@Order(-2)
public final class ProblemHandler implements WebExceptionHandler {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted())
            return Mono.error(error);
        int status = 503;
        String code = HttpConstants.DEPENDENCY_UNAVAILABLE;
        if (error instanceof DomainFailure failure) {
            status = failure.status();
            code = failure.code();
        } else if (error instanceof DecodingException || error instanceof DataBufferLimitException
            || error instanceof IllegalArgumentException) {
            status = 400;
            code = HttpConstants.INVALID_INPUT;
        } else if (error instanceof ResponseStatusException response) {
            status = response.getStatusCode().value();
            code = HttpConstants.HTTP_ERROR;
        }
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] bytes = mapper
            .writeValueAsBytes(Map.of(HttpConstants.PROBLEM_TYPE, HttpConstants.PROBLEM_TYPE_PREFIX + code,
                HttpConstants.PROBLEM_TITLE, code, HttpConstants.PROBLEM_STATUS, status,
                HttpConstants.PROBLEM_CODE, code, HttpConstants.TRACE_ID, exchange.getRequest().getId()));
        return exchange.getResponse()
            .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
