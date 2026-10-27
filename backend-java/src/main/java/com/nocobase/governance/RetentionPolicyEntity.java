package com.nocobase.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 数据保留策略(B7)。
 *
 * <p>按 {@code collectionName} 配置保留天数与到期动作:
 * <ul>
 *   <li>{@code ARCHIVE} — 置 {@code collection_meta.read_only=true}(冻结写入)</li>
 *   <li>{@code DELETE} — 删除 {@code collection_meta} 元数据行(物理 DROP 仅记审计,留人工)</li>
 * </ul>
 */
@Entity
@Table(name = "retention_policy")
public class RetentionPolicyEntity {

    /** 到期动作。 */
    public enum Action { ARCHIVE, DELETE }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "collection_name", nullable = false, length = 64)
    private String collectionName;

    @Column(name = "action", nullable = false, length = 16)
    private String action = "ARCHIVE";

    @Column(name = "retention_days", nullable = false)
    private Integer retentionDays = 365;

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public RetentionPolicyEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Integer getRetentionDays() { return retentionDays; }
    public void setRetentionDays(Integer retentionDays) { this.retentionDays = retentionDays; }

    public Instant getLastExecutedAt() { return lastExecutedAt; }
    public void setLastExecutedAt(Instant lastExecutedAt) { this.lastExecutedAt = lastExecutedAt; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
