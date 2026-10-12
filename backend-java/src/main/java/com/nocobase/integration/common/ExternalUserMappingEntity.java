package com.nocobase.integration.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * PHASE96 T1: 第三方平台用户 → 我方用户的映射（持久化，按租户隔离）。
 *
 * <p>唯一键 {@code (platform, external_user_id, tenant_id)}，
 * 与 {@code ldap_user_mapping}（V28）的范式一致。
 */
@Entity
@Table(
        name = "external_user_mapping",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_external_user_mapping",
                columnNames = {"platform", "external_user_id", "tenant_id"}),
        indexes = {
                @Index(name = "idx_external_user_mapping_lookup",
                        columnList = "platform, external_user_id, tenant_id"),
                @Index(name = "idx_external_user_mapping_local", columnList = "local_user_id")
        })
public class ExternalUserMappingEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "platform", nullable = false, length = 64)
    private String platform;

    @Column(name = "external_user_id", nullable = false, length = 256)
    private String externalUserId;

    @Column(name = "local_user_id")
    private UUID localUserId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "metadata", nullable = false, length = 4000)
    private String metadata = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getExternalUserId() { return externalUserId; }
    public void setExternalUserId(String externalUserId) { this.externalUserId = externalUserId; }
    public UUID getLocalUserId() { return localUserId; }
    public void setLocalUserId(UUID localUserId) { this.localUserId = localUserId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
