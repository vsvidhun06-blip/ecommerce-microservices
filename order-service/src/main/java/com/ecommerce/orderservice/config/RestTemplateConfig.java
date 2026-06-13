package com.ecommerce.orderservice.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Outbound HTTP client for synchronous service-to-service calls.
 *
 * The {@link LoadBalanced} RestTemplate resolves {@code lb://<service-id>} URLs
 * through Spring Cloud LoadBalancer against the Eureka registry, so callers use
 * the logical service id (e.g. {@code lb://product-service}) instead of a
 * hardcoded host:port. This replaces the previous {@code new RestTemplate()}
 * pinned to {@code localhost:8081}, which was unreachable from inside the
 * Docker network.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
