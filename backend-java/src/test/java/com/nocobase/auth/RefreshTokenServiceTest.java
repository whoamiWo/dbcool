package com.nocobase.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * RefreshTokenService 单测(Week 30).
 * 测 issue/consume + Redis delete 行为 + null 返回.
 */
class RefreshTokenServiceTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private ValueOperations ops;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("rawtypes")
        ValueOperations v = mock(ValueOperations.class);
        ops = v;
        when(redis.opsForValue()).thenReturn(ops);
        service = new RefreshTokenService(redis, 7L);
    }

    @Test
    void issue_storesInRedisWithTtl() {
        UUID userId = UUID.randomUUID();
        doNothing().when(ops).set(anyString(), anyString(), any(Duration.class));

        String token = service.issue(userId);

        assertNotNull(token);
        assertTrue(token.length() > 30);  // 32 字节 base64 url 无 padding
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCap = ArgumentCaptor.forClass(Duration.class);
        verify(ops).set(keyCap.capture(), valCap.capture(), ttlCap.capture());
        assertTrue(keyCap.getValue().startsWith("refresh:"));
        assertEquals(userId.toString(), valCap.getValue());
        assertEquals(Duration.ofDays(7), ttlCap.getValue());
    }

    @Test
    void consume_existingToken_returnsUserIdAndDeletes() {
        UUID userId = UUID.randomUUID();
        String token = "tok-abc";
        when(ops.get("refresh:" + token)).thenReturn(userId.toString());

        UUID returned = service.consume(token);

        assertEquals(userId, returned);
        verify(redis, times(1)).delete("refresh:" + token);
    }

    @Test
    void consume_nonExistentToken_returnsNull() {
        when(ops.get(anyString())).thenReturn(null);

        UUID returned = service.consume("unknown-token");

        assertNull(returned);
        verify(redis, never()).delete(anyString());
    }
}
