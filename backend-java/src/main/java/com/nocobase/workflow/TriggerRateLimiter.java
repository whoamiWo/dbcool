package com.nocobase.workflow;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 触发器频率限制器(Week 41 D4a — 报告 C-R04 死循环防护).
 *
 * <p>同一 record_id 在 1 分钟内最多触发 N 次(N 默认 5)。
 * 超出则阻断并记录日志。
 *
 * <p>实现 Redis-backed 滑动窗口，支持多实例共享计数；
 * Redis 不可用时自动回退到内存实现。
 */
@Component
public class TriggerRateLimiter {

    /** 窗口大小(秒) */
    private static final long WINDOW_SECONDS = 60;

    /** 窗口内最大触发次数 */
    private static final int MAX_TRIGGERS = 5;

    /** Redis key 前缀 */
    private static final String REDIS_KEY_PREFIX = "workflow:rate:";

    private final StringRedisTemplate redisTemplate;
    private final MemoryRateLimiter memoryLimiter;

    public TriggerRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.memoryLimiter = new MemoryRateLimiter();
    }

    /** 检查是否允许触发(返回 true=允许,false=被限制)。 */
    public boolean allowTrigger(String workflowId, String recordId) {
        if (workflowId == null || recordId == null) return true; // 无 recordId 不限
        String key = workflowId + "|" + recordId;

        if (redisTemplate.getConnectionFactory() != null) {
            try {
                return allowTriggerRedis(key);
            } catch (Exception e) {
                // Redis 失败时回退到内存
                return memoryLimiter.allowTrigger(workflowId, recordId);
            }
        }
        return memoryLimiter.allowTrigger(workflowId, recordId);
    }

    private boolean allowTriggerRedis(String key) {
        String redisKey = REDIS_KEY_PREFIX + key;
        long now = System.currentTimeMillis() / 1000L;
        long cutoff = now - WINDOW_SECONDS;

        // 1. 移除过期时间戳 (ZREMRANGEBYSCORE)
        redisTemplate.boundZSetOps(redisKey).removeRangeByScore(0, cutoff);

        // 2. 检查当前数量 (ZCARD)
        Long count = redisTemplate.boundZSetOps(redisKey).zCard();
        if (count != null && count >= MAX_TRIGGERS) {
            return false;
        }

        // 3. 添加当前时间戳 (ZADD)
        redisTemplate.boundZSetOps(redisKey).add(String.valueOf(now), now);

        // 4. 设置过期时间 (EXPIRE)，防止无限增长
        redisTemplate.boundZSetOps(redisKey).expire(java.time.Duration.ofSeconds(WINDOW_SECONDS + 10));

        return true;
    }

    /** 测试 / 清理用:清空所有计数。 */
    public void reset() {
        memoryLimiter.reset();
        // 同时清理 Redis (扫描前缀删除)
        if (redisTemplate.getConnectionFactory() != null) {
            var keys = redisTemplate.keys(REDIS_KEY_PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    /** 测试 / 监控用:当前 key 的触发次数。 */
    public int countFor(String workflowId, String recordId) {
        String key = workflowId + "|" + recordId;
        try {
            if (redisTemplate.getConnectionFactory() != null) {
                String redisKey = REDIS_KEY_PREFIX + key;
                Long count = redisTemplate.boundZSetOps(redisKey).zCard();
                return count != null ? count.intValue() : 0;
            }
        } catch (Exception e) {
            // Redis 失败回退
        }
        return memoryLimiter.countFor(workflowId, recordId);
    }

    // ==================== 内存回退实现 ====================

    /**
     * 内存版滑动窗口限流器。
     * 用于 Redis 不可用时的回退，或单元测试。
     */
    private static class MemoryRateLimiter {
        private static final int EVICT_THRESHOLD = 1024;
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

        boolean allowTrigger(String workflowId, String recordId) {
            String key = workflowId + "|" + recordId;
            Deque<Long> window = hits.computeIfAbsent(key, k -> new LinkedList<>());

            long now = System.currentTimeMillis();
            long cutoff = now - WINDOW_SECONDS * 1000;

            // 清掉窗口外的旧时间戳
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.pollFirst();
            }

            boolean allowed = window.size() < MAX_TRIGGERS;
            if (allowed) {
                window.addLast(now);
            }

            // 内存泄漏防护。必须在 addLast 之后执行 —— 否则刚 computeIfAbsent 出来的
            // 空窗口会被误删,本次计数将写进一个已从 map 移除的 deque(计数丢失)。
            if (hits.size() > EVICT_THRESHOLD) {
                evictEmptyEntries(cutoff);
            }
            return allowed;
        }

        int countFor(String workflowId, String recordId) {
            String key = workflowId + "|" + recordId;
            Deque<Long> window = hits.get(key);
            return window == null ? 0 : window.size();
        }

        void reset() {
            hits.clear();
        }

        private void evictEmptyEntries(long cutoff) {
            hits.entrySet().removeIf(e -> {
                Deque<Long> dq = e.getValue();
                while (!dq.isEmpty() && dq.peekFirst() < cutoff) {
                    dq.pollFirst();
                }
                return dq.isEmpty();
            });
        }
    }
}
