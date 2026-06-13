package com.ecommerce.productservice.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Minimal Redis distributed lock (single-node / SET NX PX semantics).
 *
 * {@link #tryLock} acquires a key only if absent, with a TTL so a crashed holder
 * cannot deadlock the key forever. {@link #unlock} releases it via a Lua
 * compare-and-delete keyed on a per-holder token, so a caller can never delete a
 * lock that a later holder acquired after its own TTL expired.
 *
 * This guards the cache-aside repopulation path against a cache stampede: on a
 * miss, only the lock holder hits the database, while concurrent readers wait
 * briefly for the freshly cached value.
 */
@Component
public class DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockService.class);

    private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public DistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** @return true if this caller now holds {@code key} for up to {@code ttl}. */
    public boolean tryLock(String key, String token, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /** Release {@code key} only if still held by {@code token}. */
    public void unlock(String key, String token) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
        } catch (RuntimeException e) {
            // Lock auto-expires via TTL; a failed release is not fatal.
            log.warn("Failed to release lock {}: {}", key, e.toString());
        }
    }
}
