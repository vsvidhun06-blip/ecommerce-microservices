package com.ecommerce.orderservice.client;

import com.ecommerce.orderservice.dto.ProductDTO;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CompletableFuture;

/**
 * Resilient client for the product service's pricing lookup.
 *
 * The call is isolated behind a Resilience4j <b>thread-pool bulkhead</b>
 * ({@code productService} instance, tuned in application.properties): product
 * lookups run on a dedicated, bounded pool with a bounded queue, so a slow or
 * unavailable product service cannot exhaust the order service's Tomcat request
 * threads — back-pressure surfaces quickly instead of cascading.
 *
 * Because the thread-pool bulkhead executes the call on its own pool, the method
 * returns a {@link CompletableFuture}; the caller joins it. When the bulkhead's
 * queue is saturated (or the call fails) the {@link #getProductFallback fallback}
 * completes exceptionally so the order flow gets a clean, typed failure.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductClient {

    private final RestTemplate restTemplate;

    /** Logical service id resolved via Spring Cloud LoadBalancer / Eureka. */
    @Value("${product.service.url:lb://product-service}")
    private String productServiceBaseUrl;

    @Bulkhead(name = "productService", type = Bulkhead.Type.THREADPOOL,
            fallbackMethod = "getProductFallback")
    public CompletableFuture<ProductDTO> getProduct(Long productId) {
        String url = productServiceBaseUrl + "/api/v1/products/" + productId;
        log.debug("Fetching product {} from {}", productId, url);
        ProductDTO product = restTemplate.getForObject(url, ProductDTO.class);
        return CompletableFuture.completedFuture(product);
    }

    /**
     * Fallback for a saturated bulkhead (BulkheadFullException) or a failed
     * lookup. Surfaces a typed, exceptional future rather than a raw rejection so
     * the order flow can translate it into a clean error.
     */
    @SuppressWarnings("unused")
    private CompletableFuture<ProductDTO> getProductFallback(Long productId, Throwable t) {
        log.warn("Product lookup for {} rejected/failed by bulkhead: {}", productId, t.toString());
        return CompletableFuture.failedFuture(
                new ProductLookupException(productId, t));
    }

    /** Thrown when the product service is unavailable or the bulkhead is full. */
    public static class ProductLookupException extends RuntimeException {
        public ProductLookupException(Long productId, Throwable cause) {
            super("Product lookup failed for product " + productId + ": " + cause.getMessage(), cause);
        }
    }
}
