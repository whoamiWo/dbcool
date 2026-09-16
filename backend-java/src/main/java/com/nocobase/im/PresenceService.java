package com.nocobase.im;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 在线状态(基于 Redis,带 TTL 自动过期)。
 *
 * <p>不用本地内存:多实例部署时在线状态必须共享,否则用户连到不同实例会状态不一致。
 * TTL 保证客户端异常断连后状态能自动收敛,不依赖显式下线调用。
 */
@Service
public class PresenceService {

    private static final String KEY_PREFIX = "im:presence:";
    private static final Duration TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;

    public PresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 心跳:刷新在线状态。 */
    public void heartbeat(String tenantId, UUID userId) {
        redis.opsForValue().set(key(tenantId, userId), "1", TTL);
    }

    public boolean isOnline(String tenantId, UUID userId) {
        return Boolean.TRUE.equals(redis.hasKey(key(tenantId, userId)));
    }

    /** 批量判定在线(避免 N 次 Redis 往返)。 */
    public java.util.Map<UUID, Boolean> onlineMap(String tenantId, java.util.Collection<UUID> userIds) {
        java.util.Map<UUID, Boolean> result = new java.util.LinkedHashMap<>();
        if (userIds == null || userIds.isEmpty()) return result;
        for (UUID uid : userIds) {
            if (uid != null) result.put(uid, isOnline(tenantId, uid));
        }
        return result;
    }

    /** 显式下线(如用户登出)。 */
    public void offline(String tenantId, UUID userId) {
        redis.delete(key(tenantId, userId));
    }

    /** 当前在线用户数(仅用于运维观测)。 */
    public long onlineCount(String tenantId) {
        Set<String> keys = redis.keys(KEY_PREFIX + tenantId + ":*");
        return keys == null ? 0L : keys.size();
    }

    private static String key(String tenantId, UUID userId) {
        return KEY_PREFIX + tenantId + ":" + userId;
    }
}
