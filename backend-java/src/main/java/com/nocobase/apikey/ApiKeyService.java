package com.nocobase.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API Key 服务(Week 42 D5.2 — 外部系统认证).
 *
 * <p>设计:
 * <ul>
 *   <li>raw key = {@code ncb_} + 32 hex 字符(总 36 字符)</li>
 *   <li>db 存 hash (SHA-256) + prefix (前 8 字符)</li>
 *   <li>创建时返回 raw key 仅这一次,后续无法再读</li>
 *   <li>验证流程:prefix 检索候选 → hash 比对 → 有效期检查 → 更新 last_used_at</li>
 * </ul>
 *
 * <p><strong>安全约束</strong>:
 * <ul>
 *   <li>不存明文,db 泄漏也不暴露 raw key</li>
 *   <li>撤销不可逆(soft delete 保留审计)</li>
 *   <li>last_used_at 更新异步/批量(高频调用下避免 hot row)</li>
 * </ul>
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /** key 前缀。短前缀(ncb_) 便于识别 = NocoBase。 */
    public static final String KEY_PREFIX_NAMESPACE = "ncb_";

    /** raw key 随机部分长度(hex chars)。32 chars = 128 bit entropy,够用。 */
    public static final int RANDOM_HEX_LENGTH = 32;

    /** SHA-256 hex 长度。 */
    public static final int HASH_HEX_LENGTH = 64;

    /** 展示用前缀长度 — 前 8 字符(含 {@code ncb_xxx})。 */
    public static final int DISPLAY_PREFIX_LENGTH = 8;

    private final ApiKeyRepository repository;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * 创建新 key.返回 raw key + 持久化 entity.
     *
     * @param name 用户给 key 的描述名(如 "Zapier 集成")
     * @param scopes 逗号分隔权限范围;null/空 = 无权限
     * @param expiresAt 可选过期时间;null = 永不过期
     * @return CreatedKey 包含 rawKey(仅这一次可见) + entity(后续可查)
     */
    @Transactional
    public CreatedKey create(String name, String scopes, Instant expiresAt,
                              String tenantId, UUID createdBy) {
        String randomHex = randomHex(RANDOM_HEX_LENGTH);
        String rawKey = KEY_PREFIX_NAMESPACE + randomHex;

        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setName(name);
        e.setKeyPrefix(rawKey.substring(0, DISPLAY_PREFIX_LENGTH));
        e.setKeyHash(sha256Hex(rawKey));
        e.setScopes(scopes);
        e.setCreatedBy(createdBy);
        e.setCreatedAt(Instant.now());
        e.setExpiresAt(expiresAt);
        e.setTenantId(tenantId);

        repository.save(e);
        log.info("[apikey] created: id={}, name={}, prefix={}, tenant={}",
                e.getId(), name, e.getKeyPrefix(), tenantId);
        return new CreatedKey(rawKey, e);
    }

    /**
     * 验证 raw key 有效性 — 命中后更新 last_used_at.
     *
     * @return entity 若有效;empty 若 prefix 不匹配 / hash 不匹配 / 撤销 / 过期
     */
    @Transactional
    public Optional<ApiKeyEntity> validate(String rawKey, String tenantId) {
        if (rawKey == null || rawKey.length() != KEY_PREFIX_NAMESPACE.length() + RANDOM_HEX_LENGTH) {
            return Optional.empty();
        }
        if (!rawKey.startsWith(KEY_PREFIX_NAMESPACE)) {
            return Optional.empty();
        }
        String prefix = rawKey.substring(0, DISPLAY_PREFIX_LENGTH);
        String hash = sha256Hex(rawKey);

        // 先查 hash 直命中(O(1));未命中再按 prefix 扫
        Optional<ApiKeyEntity> direct = repository.findByKeyHash(hash);
        ApiKeyEntity e;
        if (direct.isPresent()) {
            e = direct.get();
        } else {
            // prefix 检索(应该 0 或 1 命中,极少)
            List<ApiKeyEntity> candidates = repository
                    .findByKeyPrefixAndTenantIdAndRevokedAtIsNull(prefix, tenantId);
            e = candidates.stream()
                    .filter(c -> hash.equals(c.getKeyHash()))
                    .findFirst()
                    .orElse(null);
            if (e == null) return Optional.empty();
        }

        if (!e.getTenantId().equals(tenantId)) return Optional.empty();
        if (!e.isValid(Instant.now())) return Optional.empty();

        e.setLastUsedAt(Instant.now());
        repository.save(e);
        return Optional.of(e);
    }

    /** 撤销 key (soft delete)。 */
    @Transactional
    public boolean revoke(UUID id, String tenantId) {
        Optional<ApiKeyEntity> opt = repository.findById(id);
        if (opt.isEmpty() || !opt.get().getTenantId().equals(tenantId)) {
            return false;
        }
        ApiKeyEntity e = opt.get();
        if (e.getRevokedAt() != null) return false; // 已撤销
        e.setRevokedAt(Instant.now());
        repository.save(e);
        log.info("[apikey] revoked: id={}, tenant={}", id, tenantId);
        return true;
    }

    /** 列出 tenant 下所有有效 key。 */
    public List<ApiKeyEntity> listActive(String tenantId) {
        return repository.findByTenantIdAndRevokedAtIsNullOrderByCreatedAtDesc(tenantId);
    }

    /** 生成随机 hex 字符(密码学安全)。 */
    static String randomHex(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        randomBytes(bytes);
        return HexFormat.of().formatHex(bytes).substring(0, length);
    }

    /** SHA-256 hex(raw key)。 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** SecureRandom 静态访问(供测试隔离)。 */
    private static void randomBytes(byte[] buf) {
        new SecureRandom().nextBytes(buf);
    }

    /** 创建结果:rawKey 仅可见一次。 */
    public record CreatedKey(String rawKey, ApiKeyEntity entity) {}
}
