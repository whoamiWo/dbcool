package com.nocobase.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class GlobalRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(GlobalRateLimiter.class);
    private static final String REDIS_KEY_PREFIX = "ratelimit:";
    // PHASE92: 租户配额使用独立前缀，避免与接口限流键冲突
    private static final String TENANT_QUOTA_PREFIX = "quota:";

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
        // 采样式清理：约每 64 次请求做一次 stale key 回收，避免改动主类开启调度
        if (ThreadLocalRandom.current().nextInt(64) == 0) {
            memoryLimiter.evictStale();
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

        // member 必须唯一：同一秒内多次请求若共用同一 member 会被 ZSET 去重，
        // 导致计数永远达不到阈值（Phase 60 R1 实测根因）。
        String member = now + "-" + System.nanoTime();
        redisTemplate.boundZSetOps(redisKey).add(member, now);
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
        private final java.util.Map<String, Long> lastAccess = new ConcurrentHashMap<>();
        private static final long STALE_MS = 5 * 60 * 1000L;

        boolean allowRequest(String key, int limit, int windowSeconds) {
            long now = System.currentTimeMillis() / 1000L;
            long cutoff = now - windowSeconds;

            Deque<Long> timestamps = cache.computeIfAbsent(key, k -> new LinkedList<>());
            lastAccess.put(key, System.currentTimeMillis());

            synchronized (timestamps) {
                while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                    timestamps.pollFirst();
                }

                if (timestamps.size() >= limit) {
                    return false;
                }

                timestamps.addLast(now);
                return true;
            }
        }

        void reset(String key) {
            cache.remove(key);
            lastAccess.remove(key);
        }

        /**
         * 清理超过 5 分钟未访问的 key，防止长期累积内存泄漏。
         * 由外部（如 @Scheduled）定期调用。
         */
        void evictStale() {
            long now = System.currentTimeMillis();
            lastAccess.entrySet().removeIf(e -> {
                boolean stale = now - e.getValue() > STALE_MS;
                if (stale) cache.remove(e.getKey());
                return stale;
            });
        }
    }
}