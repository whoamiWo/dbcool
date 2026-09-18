package com.nocobase.integration.dingtalk;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户映射服务 — 建立钉钉 unionid ↔ 平台 userId 的映射关系。
 *
 * <p>用于组织架构同步时，将钉钉通讯录用户与本地账号关联，
 * 确保多次同步时同一钉钉用户不会重复建号。
 */
@Service
public class UserMappingService {

    private static final Logger log = LoggerFactory.getLogger(UserMappingService.class);

    private final UserRepository userRepository;

    // 内存缓存：unionid → userId 映射（生产环境可替换为 Redis/数据库表）
    private final Map<String, UUID> mappingCache = new HashMap<>();

    public UserMappingService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 建立或获取钉钉 unionid 与平台 userId 的映射。
     *
     * @param dingtalkUserId 钉钉用户标识（unionid 或 openid）
     * @param userId          平台用户 UUID
     * @param tenantId        租户 ID
     * @return 映射成功的用户实体
     */
    public UserEntity mapUser(String dingtalkUserId, String userId, String tenantId) {
        UUID platformUserId = UUID.fromString(userId);

        // 先检查缓存
        UUID cachedId = mappingCache.get(dingtalkUserId);
        if (cachedId != null && cachedId.equals(platformUserId)) {
            log.debug("[DingTalk] 映射已存在: unionid={} → userId={}", dingtalkUserId, userId);
            return userRepository.findById(platformUserId)
                    .orElseThrow(() -> new IllegalArgumentException("映射用户不存在: " + userId));
        }

        // 更新缓存
        mappingCache.put(dingtalkUserId, platformUserId);
        log.info("[DingTalk] 新建映射: unionid={} → userId={} (tenant={})",
                dingtalkUserId, userId, tenantId);

        return userRepository.findById(platformUserId)
                .orElseThrow(() -> new IllegalArgumentException("映射用户不存在: " + userId));
    }

    /**
     * 根据钉钉 unionid 查找平台用户。
     */
    public Optional<UserEntity> findByDingTalkUserId(String dingtalkUserId) {
        UUID userId = mappingCache.get(dingtalkUserId);
        if (userId != null) {
            return userRepository.findById(userId);
        }
        return Optional.empty();
    }

    /**
     * 获取所有映射关系（用于调试/审计）。
     */
    public Map<String, UUID> getAllMappings() {
        return new HashMap<>(mappingCache);
    }

    /**
     * 获取租户用户映射列表。
     */
    public List<Map<String, Object>> getMappings(String tenantId) {
        return mappingCache.entrySet().stream()
                .map(e -> {
                    try {
                        UserEntity user = userRepository.findById(e.getValue()).orElse(null);
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("dingtalkUserId", e.getKey());
                        m.put("platformUserId", e.getValue().toString());
                        m.put("username", user != null ? user.getUsername() : "");
                        m.put("displayName", user != null ? user.getDisplayName() : "");
                        m.put("tenantId", tenantId);
                        return m;
                    } catch (Exception ex) {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("dingtalkUserId", e.getKey());
                        m.put("platformUserId", e.getValue().toString());
                        m.put("username", "");
                        m.put("displayName", "");
                        m.put("tenantId", tenantId);
                        return m;
                    }
                })
                .toList();
    }
}
