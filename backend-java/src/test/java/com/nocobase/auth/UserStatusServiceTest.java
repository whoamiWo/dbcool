package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** A-5：账号活跃状态缓存（fail-closed + 主动失效）。 */
class UserStatusServiceTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private ValueOperations ops;
    private UserRepository userRepository;
    private RefreshTokenService refreshTokenService;
    private UserStatusService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        userRepository = mock(UserRepository.class);
        refreshTokenService = mock(RefreshTokenService.class);
        service = new UserStatusService(redis, userRepository, refreshTokenService);
    }

    private UserEntity user(boolean enabled, String status) {
        UserEntity u = new UserEntity();
        u.setId(userId);
        u.setEnabled(enabled);
        u.setStatus(status);
        return u;
    }

    @Test
    @DisplayName("缓存命中 true → 不查 DB")
    void isActive_cacheHitTrue() {
        when(ops.get("user:active:" + userId)).thenReturn("1");

        assertThat(service.isActive(userId)).isTrue();
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("缓存命中 false → 直接拒绝")
    void isActive_cacheHitFalse() {
        when(ops.get("user:active:" + userId)).thenReturn("0");
        assertThat(service.isActive(userId)).isFalse();
    }

    @Test
    @DisplayName("缓存未命中 → 查 DB 并回填 60s")
    void isActive_cacheMiss_queriesDbAndCaches() {
        when(ops.get("user:active:" + userId)).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(true, UserStatus.ACTIVE)));

        assertThat(service.isActive(userId)).isTrue();
        verify(ops).set(eq("user:active:" + userId), eq("1"), any());
    }

    @Test
    @DisplayName("禁用/离职 → false")
    void isActive_disabledOrResigned() {
        when(ops.get("user:active:" + userId)).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(false, UserStatus.ACTIVE)));
        assertThat(service.isActive(userId)).isFalse();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user(true, UserStatus.RESIGNED)));
        assertThat(service.isActive(userId)).isFalse();
    }

    @Test
    @DisplayName("用户不存在 → fail-closed false 并缓存负结果")
    void isActive_userMissing_failClosed() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertThat(service.isActive(userId)).isFalse();
        verify(ops).set(eq("user:active:" + userId), eq("0"), any());
    }

    @Test
    @DisplayName("DB 异常 → fail-closed false（不放行）")
    void isActive_dbError_failClosed() {
        when(userRepository.findById(userId)).thenThrow(new RuntimeException("db down"));
        assertThat(service.isActive(userId)).isFalse();
    }

    @Test
    @DisplayName("Redis 异常 → 降级查 DB，不影响判定")
    void isActive_redisError_degradesToDb() {
        when(ops.get(any())).thenThrow(new RuntimeException("redis down"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(true, UserStatus.ACTIVE)));

        assertThat(service.isActive(userId)).isTrue();
    }

    @Test
    @DisplayName("invalidate：清缓存 + 吊销 refresh")
    void invalidate_clearsCacheAndRevokesRefresh() {
        when(refreshTokenService.revokeAll(userId)).thenReturn(3L);

        long revoked = service.invalidate(userId);

        assertThat(revoked).isEqualTo(3);
        verify(redis).delete("user:active:" + userId);
        verify(refreshTokenService).revokeAll(userId);
    }

    @Test
    @DisplayName("invalidate：Redis 故障也要吊销 refresh")
    void invalidate_redisError_stillRevokesRefresh() {
        when(redis.delete(anyString())).thenThrow(new RuntimeException("redis down"));
        when(refreshTokenService.revokeAll(userId)).thenReturn(1L);

        assertThat(service.invalidate(userId)).isEqualTo(1);
        verify(refreshTokenService).revokeAll(userId);
    }
}
