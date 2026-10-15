package com.nocobase.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.Map;

/**
 * GlobalRateLimiter 内存泄漏清理测试。
 * 验证 evictStale() 能正确回收长时间未访问的 key。
 */
class GlobalRateLimiterEvictionTest {

    @SuppressWarnings("unchecked")
    private Object getMemoryLimiter(GlobalRateLimiter limiter) throws Exception {
        Field f = GlobalRateLimiter.class.getDeclaredField("memoryLimiter");
        f.setAccessible(true);
        return f.get(limiter);
    }

    @Test
    void testEvictStale() throws Exception {
        GlobalRateLimiter limiter = new GlobalRateLimiter(null); // Redis null 强制走内存模式
        Object ml = getMemoryLimiter(limiter);
        Field cacheField = ml.getClass().getDeclaredField("cache");
        Field lastAccessField = ml.getClass().getDeclaredField("lastAccess");
        cacheField.setAccessible(true);
        lastAccessField.setAccessible(true);

        Map<String, Deque<Long>> cache = (Map<String, Deque<Long>>) cacheField.get(ml);
        Map<String, Long> lastAccess = (Map<String, Long>) lastAccessField.get(ml);

        // 手动注入两个 key，其中一个模拟"陈旧"
        String staleKey = "stale:test";
        String freshKey = "fresh:test";

        cache.put(staleKey, new java.util.LinkedList<>());
        cache.put(freshKey, new java.util.LinkedList<>());

        // 设置 staleKey 的最后访问时间 >5 分钟前
        long fiveMinutesAgo = System.currentTimeMillis() - 6 * 60 * 1000L;
        lastAccess.put(staleKey, fiveMinutesAgo);
        lastAccess.put(freshKey, System.currentTimeMillis());

        assertEquals(2, cache.size(), "初始应有 2 个 key");
        assertEquals(2, lastAccess.size(), "初始应有 2 个 lastAccess");

        // 通过反射调用 evictStale
        java.lang.reflect.Method m = ml.getClass().getDeclaredMethod("evictStale");
        m.setAccessible(true);
        m.invoke(ml);

        // 验证 staleKey 被移除，freshKey 保留
        assertEquals(1, cache.size(), "evict 后应剩 1 个 key");
        assertEquals(1, lastAccess.size(), "evict 后应剩 1 个 lastAccess");
        assertTrue(cache.containsKey(freshKey), "freshKey 应保留");
        assertFalse(cache.containsKey(staleKey), "staleKey 应被移除");
    }

    @Test
    void testAllowRequestNoLeak() throws Exception {
        GlobalRateLimiter limiter = new GlobalRateLimiter(null);
        Object ml = getMemoryLimiter(limiter);
        Field cacheField = GlobalRateLimiter.MemoryRateLimiter.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Map<String, Deque<Long>> cache = (Map<String, Deque<Long>>) cacheField.get(ml);

        // 连续请求 100 次不同 key
        for (int i = 0; i < 100; i++) {
            limiter.allowRequest("key:" + i, 10, 60);
        }

        // 正常情况下 cache 大小应为 100（都在窗口内）
        assertEquals(100, cache.size(), "100 个活跃 key 应全在 cache");

        // 手动老化一半 key
        Field lastAccessField = GlobalRateLimiter.MemoryRateLimiter.class.getDeclaredField("lastAccess");
        lastAccessField.setAccessible(true);
        Map<String, Long> lastAccess = (Map<String, Long>) lastAccessField.get(ml);

        for (int i = 0; i < 50; i++) {
            lastAccess.put("key:" + i, System.currentTimeMillis() - 6 * 60 * 1000L);
        }

        ((GlobalRateLimiter.MemoryRateLimiter) ml).evictStale();

        // 验证只保留 50 个新鲜 key
        assertEquals(50, cache.size(), "evict 后应剩 50 个新鲜 key");
    }
}
