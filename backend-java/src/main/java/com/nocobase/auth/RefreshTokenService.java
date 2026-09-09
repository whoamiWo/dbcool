package com.nocobase.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh Token 服务.
 *
 * <p>用 Redis 存储,key = refresh:{token},value = userId.
 * 单次使用:刷新后删除旧 token.
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RefreshTokenService(
            StringRedisTemplate redis,
            @Value("${app.jwt.refresh-ttl-days:7}") long refreshTtlDays
    ) {
        this.redis = redis;
        this.ttl = Duration.ofDays(refreshTtlDays);
    }

    /**
     * 生成新的 refresh token.
     */
    public String issue(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(KEY_PREFIX + token, userId.toString(), ttl);
        return token;
    }

    /**
     * 取出 userId 并删除(单次使用).
     *
     * @return userId;若 token 不存在返回 null
     */
    public UUID consume(String token) {
        String key = KEY_PREFIX + token;
        String userIdStr = redis.opsForValue().get(key);
        if (userIdStr == null) {
            return null;
        }
        redis.delete(key);
        return UUID.fromString(userIdStr);
    }
}
