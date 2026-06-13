package com.ecommerce.productservice.service;

import com.ecommerce.productservice.lock.DistributedLockService;
import com.ecommerce.productservice.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Cache-aside access to {@link Product} reads, backed by Redis and guarded by a
 * distributed lock to prevent a cache stampede.
 *
 * Read path ({@link #getOrLoad}):
 * <ol>
 *   <li>Return the cached value on a hit.</li>
 *   <li>On a miss, try to acquire {@code lock:product:{id}}. The holder
 *       double-checks the cache (another holder may have just filled it), then
 *       loads from the supplied loader (DB), caches it, and releases the lock.</li>
 *   <li>A caller that loses the lock waits briefly for the holder to publish the
 *       value; if it still isn't there, it falls back to loading directly so a
 *       slow holder never blocks the request.</li>
 * </ol>
 *
 * Every Redis interaction degrades gracefully: if Redis is unreachable the cache
 * is simply bypassed (load from the loader) rather than failing the request.
 */
@Service
public class ProductCacheService {

    private static final Logger log = LoggerFactory.getLogger(ProductCacheService.class);

    private static final String CACHE_PREFIX = "product:";
    private static final String LOCK_PREFIX = "lock:product:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final int CONTENDED_WAIT_ATTEMPTS = 10;
    private static final long CONTENDED_WAIT_MILLIS = 50L;

    private final RedisTemplate<String, Product> redisTemplate;
    private final DistributedLockService lockService;

    @Value("${product.cache.ttl-seconds:600}")
    private long cacheTtlSeconds;

    public ProductCacheService(RedisTemplate<String, Product> redisTemplate,
                               DistributedLockService lockService) {
        this.redisTemplate = redisTemplate;
        this.lockService = lockService;
    }

    public Product getOrLoad(Long id, Supplier<Product> loader) {
        Product cached = read(id);
        if (cached != null) {
            log.debug("Cache hit for product {}", id);
            return cached;
        }

        String lockKey = LOCK_PREFIX + id;
        String token = UUID.randomUUID().toString();
        if (lockService.tryLock(lockKey, token, LOCK_TTL)) {
            try {
                // Double-check: a previous holder may have populated the cache
                // between our miss and acquiring the lock.
                Product second = read(id);
                if (second != null) {
                    return second;
                }
                log.debug("Cache miss for product {}; loading from source (lock held)", id);
                Product loaded = loader.get();
                put(loaded);
                return loaded;
            } finally {
                lockService.unlock(lockKey, token);
            }
        }

        // Lost the lock: another loader is in flight. Wait briefly for the value.
        for (int attempt = 0; attempt < CONTENDED_WAIT_ATTEMPTS; attempt++) {
            sleep(CONTENDED_WAIT_MILLIS);
            Product value = read(id);
            if (value != null) {
                return value;
            }
        }
        log.debug("Cache still cold for product {} after waiting; loading directly", id);
        return loader.get();
    }

    public void put(Product product) {
        if (product == null || product.getId() == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    CACHE_PREFIX + product.getId(), product, Duration.ofSeconds(cacheTtlSeconds));
        } catch (RuntimeException e) {
            log.warn("Failed to cache product {}: {}", product.getId(), e.toString());
        }
    }

    public void evict(Long id) {
        try {
            redisTemplate.delete(CACHE_PREFIX + id);
        } catch (RuntimeException e) {
            log.warn("Failed to evict product {} from cache: {}", id, e.toString());
        }
    }

    private Product read(Long id) {
        try {
            return redisTemplate.opsForValue().get(CACHE_PREFIX + id);
        } catch (RuntimeException e) {
            log.warn("Redis read failed for product {} (bypassing cache): {}", id, e.toString());
            return null;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
