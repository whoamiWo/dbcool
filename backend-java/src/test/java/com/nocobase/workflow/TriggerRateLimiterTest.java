package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.BoundZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * PHASE104: TriggerRateLimiter 覆盖率提升测试。
 * 覆盖：内存回退路径、Redis 路径、超限阻断、reset/countFor。
 */
class TriggerRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private TriggerRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        // 默认无 connection factory → 走内存路径
        when(redisTemplate.getConnectionFactory()).thenReturn(null);
        limiter = new TriggerRateLimiter(redisTemplate);
    }

    @Test
    void nullRecordId_alwaysAllowed() {
        assertTrue(limiter.allowTrigger("wf-1", null));
        assertTrue(limiter.allowTrigger(null, "rec-1"));
    }

    @Test
    void memoryPath_allowsUpToMaxThenBlocks() {
        String wf = "wf-mem";
        String rec = "rec-mem";

        // 前 5 次允许
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowTrigger(wf, rec), "第 " + (i + 1) + " 次应允许");
        }
        // 第 6 次阻断
        assertFalse(limiter.allowTrigger(wf, rec), "超过 5 次应阻断");
    }

    @Test
    void memoryPath_differentRecords_independent() {
        assertTrue(limiter.allowTrigger("wf", "rec-1"));
        assertTrue(limiter.allowTrigger("wf", "rec-2"));
        assertTrue(limiter.allowTrigger("wf", "rec-3"));
        // 各自独立计数，不互相影响
        assertEquals(1, limiter.countFor("wf", "rec-1"));
        assertEquals(1, limiter.countFor("wf", "rec-2"));
    }

    @Test
    void memoryPath_countFor_unknownKey_returnsZero() {
        assertEquals(0, limiter.countFor("wf", "unknown"));
    }

    @Test
    void reset_clearsMemoryCounts() {
        limiter.allowTrigger("wf", "rec");
        limiter.allowTrigger("wf", "rec");
        assertEquals(2, limiter.countFor("wf", "rec"));

        limiter.reset();
        assertEquals(0, limiter.countFor("wf", "rec"));
    }

    @Test
    void redisPath_blocksAtLimit() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);

        BoundZSetOperations<String, String> zsetOps = mock(BoundZSetOperations.class);
        when(redisTemplate.boundZSetOps(anyString())).thenReturn(zsetOps);
        // 每次都返回 5（已达上限）
        when(zsetOps.zCard()).thenReturn(5L);

        // 直接超限
        assertFalse(limiter.allowTrigger("wf-redis", "rec-1"));
    }

    @Test
    void redisPath_allowsUnderLimit() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);

        BoundZSetOperations<String, String> zsetOps = mock(BoundZSetOperations.class);
        when(redisTemplate.boundZSetOps(anyString())).thenReturn(zsetOps);
        when(zsetOps.zCard()).thenReturn(3L);

        assertTrue(limiter.allowTrigger("wf-redis", "rec-1"));
    }

    @Test
    void redisPath_exception_fallsBackToMemory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(redisTemplate.boundZSetOps(anyString()))
                .thenThrow(new RuntimeException("Redis down"));

        // Redis 抛异常 → 回退内存路径，仍能正常工作
        assertTrue(limiter.allowTrigger("wf-fallback", "rec-1"));
        assertTrue(limiter.allowTrigger("wf-fallback", "rec-1"));
    }

    @Test
    void countFor_redisPath_returnsZCard() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);

        BoundZSetOperations<String, String> zsetOps = mock(BoundZSetOperations.class);
        when(redisTemplate.boundZSetOps(anyString())).thenReturn(zsetOps);
        when(zsetOps.zCard()).thenReturn(3L);

        assertEquals(3, limiter.countFor("wf", "rec"));
    }

    @Test
    void countFor_redisPath_nullCount_returnsZero() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);

        BoundZSetOperations<String, String> zsetOps = mock(BoundZSetOperations.class);
        when(redisTemplate.boundZSetOps(anyString())).thenReturn(zsetOps);
        when(zsetOps.zCard()).thenReturn(null);

        assertEquals(0, limiter.countFor("wf", "rec"));
    }

    @Test
    void reset_withRedis_deletesKeys() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of("workflow:rate:a", "workflow:rate:b"));

        limiter.reset();

        org.mockito.Mockito.verify(redisTemplate).delete(any(Set.class));
    }

    @Test
    void reset_withRedis_emptyKeys_noDelete() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());

        limiter.reset();

        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never()).delete(any(Set.class));
    }

    @Test
    void countFor_redisException_fallsBackToMemory() {
        // 先走内存积累计数（无 factory）
        when(redisTemplate.getConnectionFactory()).thenReturn(null);
        limiter.allowTrigger("wf-x", "rec-x");
        limiter.allowTrigger("wf-x", "rec-x");

        // 切到 Redis 但 zCard 抛异常 → 回退内存计数
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(factory);
        BoundZSetOperations<String, String> zsetOps = mock(BoundZSetOperations.class);
        when(redisTemplate.boundZSetOps(anyString())).thenReturn(zsetOps);
        when(zsetOps.zCard()).thenThrow(new RuntimeException("redis down"));

        assertEquals(2, limiter.countFor("wf-x", "rec-x"));
    }
}
