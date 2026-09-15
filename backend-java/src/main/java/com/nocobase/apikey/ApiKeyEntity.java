package com.nocobase.apikey;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * API Key 实体(Week 42 D5.2 — 集成层补完).
 *
 * <p>外部系统/脚本访问 API 用。设计要点:
 * <ul>
 *   <li><strong>前缀显示</strong>:db 只存 hash,但保留前 8 字符前缀供用户识别
 *       (类似 GitHub PAT:ghp_xxxx 显示 xxxx)</li>
 *   <li><strong>SHA-256 hash</strong>:raw key 不入库,只存 hash — 即使 db 泄漏
 *       攻击者也拿不到明文</li>
 *   <li><strong>scopes</strong>:逗号分隔的权限范围(如 "read:posts,write:posts")</li>
 *   <li><strong>过期</strong>:可选 expires_at,过期 key 验证失败</li>
 *   <li><strong>last_used_at</strong>:每次验证更新,用于检测异常使用</li>
 *   <li><strong>revoked_at</strong>:软删除,撤销后验证失败但保留审计</li>
 * </ul>
 */
@Entity
@Table(name = "api_key",
        indexes = {
                @Index(name = "idx_apikey_prefix", columnList = "key_prefix"),
                @Index(name = "idx_apikey_tenant", columnList = "tenant_id")
        })
public class ApiKeyEntity {

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "name", nullable = false, length = 128)
    public String name;

    /** 明文 key 前 8 字符(展示用)。raw key 不存。 */
    @Column(name = "key_prefix", nullable = false, length = 8)
    public String keyPrefix;

    /** SHA-256 hash(raw key)。64 字符 hex。 */
    @Column(name = "key_hash", nullable = false, length = 64)
    public String keyHash;

    /** 权限范围,逗号分隔(如 "read:posts,write:posts")。空 = 无权限。 */
    @Column(name = "scopes", length = 256)
    public String scopes;

    @Column(name = "created_by", nullable = false)
    public UUID createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    @Column(name = "expires_at")
    public Instant expiresAt;

    /** 软删除:撤销时间。null = 未撤销。 */
    @Column(name = "revoked_at")
    public Instant revokedAt;

    @Column(name = "tenant_id", nullable = false, length = 64)
    public String tenantId;

    /** 是否仍有效(未撤销且未过期)。 */
    @Transient
    public boolean isValid(Instant now) {
        if (revokedAt != null) return false;
        if (expiresAt != null && now.isAfter(expiresAt)) return false;
        return true;
    }

    // ===== 标准 setter/getter =====

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
