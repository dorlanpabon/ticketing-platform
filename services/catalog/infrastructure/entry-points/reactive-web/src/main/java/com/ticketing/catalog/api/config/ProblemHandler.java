package com.ticketing.catalog.api.config;

import com.ticketing.catalog.model.DomainFailure;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.web.server.*;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.codec.DecodingException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
@Component
@Order(-2)
public final class ProblemHandler implements WebExceptionHandler {
    private final JsonMapper mapper=JsonMapper.builder().build();
    @Override public Mono<Void> handle(ServerWebExchange exchange,Throwable error) {
        if(exchange.getResponse().isCommitted()) return Mono.error(error);
        int status=503; String code="DEPENDENCY_UNAVAILABLE";
        if(error instanceof DomainFailure failure) { status=failure.status(); code=failure.code(); }
        else if(error instanceof DecodingException || error instanceof DataBufferLimitException || error instanceof IllegalArgumentException) { status=400; code="INVALID_INPUT"; }
        else if(error instanceof ResponseStatusException response) { status=response.getStatusCode().value(); code="HTTP_ERROR"; }
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] bytes=mapper.writeValueAsBytes(Map.of("type","urn:ticketing:problem:"+code,"title",code,"status",status,"code",code,"traceId",exchange.getRequest().getId()));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
