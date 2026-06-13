package com.ecommerce.apigateway;

import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    /**
     * Per-client key for the RequestRateLimiter: the caller's IP address. Each
     * client IP gets its own token bucket, so one noisy client can't exhaust the
     * limit for everyone. Behind a proxy/LB, switch this to an X-Forwarded-For or
     * authenticated-principal key so buckets aren't shared across all clients.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
            String key = remote != null ? remote.getAddress().getHostAddress() : "unknown";
            return Mono.just(key);
        };
    }

    @Bean
    public GlobalFilter loggingFilter() {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().toString();
            String method = exchange.getRequest().getMethod().toString();

            log.info("Incoming Request: {} {}", method, path);

            return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                int statusCode = exchange.getResponse().getStatusCode().value();
                log.info("Response Status: {} for {} {}", statusCode, method, path);
            }));
        };
    }
}