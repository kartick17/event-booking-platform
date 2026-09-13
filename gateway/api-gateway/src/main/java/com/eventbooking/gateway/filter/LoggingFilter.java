package com.eventbooking.gateway.filter;

import com.eventbooking.gateway.security.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public int getOrder() {
        return -150;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String id = exchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID);
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();

        return chain.filter(exchange).doFinally(signal -> {
            long took = System.currentTimeMillis() - start;
            var status = exchange.getResponse().getStatusCode();
            log.info("[{}] {} {} -> {} ({} ms)", id, method, path, status, took);
        });
    }
}
