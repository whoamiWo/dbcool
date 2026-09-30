package com.nocobase.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GlobalRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(GlobalRateLimiter.class);
    private static final String REDIS_KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final MemoryRateLimiter memoryLimiter;

    public GlobalRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.memoryLimiter = new MemoryRateLimiter();
    }

    public boolean allowRequest(String key, int limit, int windowSeconds) {
        if (key == null || key.isBlank()) return true;

        try {
            if (redisTemplate.getConnectionFactory() != null) {
                return allowRequestRedis(key, limit, windowSeconds);
            }
        } catch (Exception e) {
            log.warn("[ratelimit] Redis 不可用，降级到内存限流 key={}", key);
        }
        return memoryLimiter.allowRequest(key, limit, windowSeconds);
    }

    private boolean allowRequestRedis(String key, int limit, int windowSeconds) {
        String redisKey = REDIS_KEY_PREFIX + key;
        long now = System.currentTimeMillis() / 1000L;
        long cutoff = now - windowSeconds;

        redisTemplate.boundZSetOps(redisKey).removeRangeByScore(0, cutoff);

        Long count = redisTemplate.boundZSetOps(redisKey).zCard();
        if (count != null && count >= limit) {
            return false;
        }

        redisTemplate.boundZSetOps(redisKey).add(String.valueOf(now), now);
        redisTemplate.boundZSetOps(redisKey).expire(java.time.Duration.ofSeconds(windowSeconds + 10));

        return true;
    }

    public void reset(String key) {
        if (key == null || key.isBlank()) return;
        String redisKey = REDIS_KEY_PREFIX + key;
        redisTemplate.delete(redisKey);
        memoryLimiter.reset(key);
    }

    static class MemoryRateLimiter {
        private final java.util.Map<String, Deque<Long>> cache = new ConcurrentHashMap<>();

        synchronized boolean allowRequest(String key, int limit, int windowSeconds) {
            long now = System.currentTimeMillis() / 1000L;
            long cutoff = now - windowSeconds;

            Deque<Long> timestamps = cache.computeIfAbsent(key, k -> new LinkedList<>());
            
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= limit) {
                return false;
            }

            timestamps.addLast(now);
            return true;
        }

        synchronized void reset(String key) {
            cache.remove(key);
        }
    }
}