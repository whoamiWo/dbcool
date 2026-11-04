package com.nocobase.auth;

import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 用户活跃状态缓存（A-5 修复的一半）。
 *
 * <p><b>问题</b>：JWT 是无状态的，access TTL 15 分钟。禁用/离职后，旧 token 在 TTL 内
 * 依然通过 {@code JwtAuthFilter} 的签名校验 —— 一个离职人员可以带着旧 token 再操作
 * 最多 15 分钟（refresh 更是 7 天）。
 *
 * <p><b>方案</b>：每请求查一次 DB 太贵，改为 Redis 缓存（TTL 60s）+ 主动失效：
 * <ul>
 *   <li>{@link #isActive(UUID)}：缓存命中直接用；未命中查 DB（{@code enabled && status==ACTIVE}）。
 *       <b>用户不存在 → 一律按不活跃处理（fail-closed）</b>，不回退缓存。</li>
 *   <li>{@link #invalidate(UUID)}：管理员改状态/删除用户时调用，立即清缓存
 *       + 吊销全部 refresh token，把「最多还能用 60 秒」的窗口进一步收窄为
 *       「老节点最多 60 秒、当前节点立即」。</li>
 *   <li>缓存值只有 "1"/"0" 两种形态，不存在“缓存穿透放大”问题：负结果同样缓存。</li>
 * </ul>
 *
 * <p>60 秒窗口是显式的可用性取舍（DB 抖动不应打挂全站鉴权），已写入任务书 D5。
 */
@Service
public class UserStatusService {

    private static final Logger log = LoggerFactory.getLogger(UserStatusService.class);
    private static final String KEY_PREFIX = "user:active:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;

    public UserStatusService(StringRedisTemplate redis,
                             UserRepository userRepository,
                             RefreshTokenService refreshTokenService) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * 该用户当前是否活跃（可登录、可被检索）。
     *
     * <p>fail-closed：DB 不可达或用户不存在都返回 false。
     */
    public boolean isActive(UUID userId) {
        if (userId == null) return false;
        String key = KEY_PREFIX + userId;
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return "1".equals(cached);
            }
        } catch (Exception e) {
            log.warn("[user-status] Redis 不可用，降级直查 DB userId={}: {}", userId, e.getMessage());
        }
        boolean active = computeFromDb(userId);
        try {
            redis.opsForValue().set(key, active ? "1" : "0", CACHE_TTL);
        } catch (Exception e) {
            log.warn("[user-status] 缓存写失败(不影响判定) userId={}: {}", userId, e.getMessage());
        }
        return active;
    }

    private boolean computeFromDb(UUID userId) {
        try {
            return userRepository.findById(userId)
                    .map(UserEntity::isActiveEmployee)
                    .orElse(false); // 用户已删除 → 视为不活跃（fail-closed）
        } catch (Exception e) {
            log.error("[user-status] DB 查询失败，fail-closed 拒绝 userId={}: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    /**
     * 主动失效：清缓存 + 吊销全部 refresh token。
     *
     * <p>供管理员禁用/删除用户、外部同步标记离职时调用。
     *
     * @return 吊销的 refresh token 数
     */
    public long invalidate(UUID userId) {
        long revoked = 0;
        try {
            redis.delete(KEY_PREFIX + userId);
        } catch (Exception e) {
            log.warn("[user-status] 缓存清除失败 userId={}: {}", userId, e.getMessage());
        }
        try {
            revoked = refreshTokenService.revokeAll(userId);
        } catch (Exception e) {
            log.warn("[user-status] refresh 吊销失败 userId={}: {}", userId, e.getMessage());
        }
        log.info("[user-status] 已失效用户会话 userId={} revokedRefreshTokens={}", userId, revoked);
        return revoked;
    }
}
