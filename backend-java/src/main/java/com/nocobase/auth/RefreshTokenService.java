package com.nocobase.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh Token 服务.
 *
 * <p>用 Redis 存储,key = refresh:{token},value = userId.
 * 单次使用:刷新后删除旧 token.
 *
 * <p>A-5（禁用/离职即失效）补充：新增 userId → tokens 的反向索引
 * {@code refresh:user:{userId}}（Redis Set），用于「禁用账号时吊销其全部 refresh」。
 * 没有这个反向索引时，只能等 7 天 TTL 自然过期 —— 等于禁用后仍有 7 天可用。
 */
@Service
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "refresh:";
    private static final String USER_INDEX_PREFIX = "refresh:user:";

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
        // 反向索引：便于按用户批量吊销
        String indexKey = USER_INDEX_PREFIX + userId;
        redis.opsForSet().add(indexKey, token);
        redis.expire(indexKey, ttl);
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
        // 顺带清理反向索引，避免 Set 无限膨胀
        try {
            redis.opsForSet().remove(USER_INDEX_PREFIX + userIdStr, token);
        } catch (Exception ignored) {
            // 索引清理失败不影响主流程（Set 自带 TTL）
        }
        return UUID.fromString(userIdStr);
    }

    /**
     * 吊销某用户的全部 refresh token（A-5：禁用/离职时调用）。
     *
     * @return 实际吊销的数量
     */
    public long revokeAll(UUID userId) {
        if (userId == null) return 0;
        String indexKey = USER_INDEX_PREFIX + userId;
        Set<String> tokens = redis.opsForSet().members(indexKey);
        long removed = 0;
        if (tokens != null) {
            for (String t : tokens) {
                Boolean ok = redis.delete(KEY_PREFIX + t);
                if (Boolean.TRUE.equals(ok)) removed++;
            }
        }
        redis.delete(indexKey);
        return removed;
    }
}
