package com.nocobase.tenant;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 用户-租户关联(US-504 应用切换).
 *
 * <p>最小模型:用户可访问多个租户,切换时更新 auth store 的 tenant_id 并刷新 JWT。
 */
@Entity
@Table(name = "user_tenant")
public class UserTenantEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserTenantEntity() {}

    public UserTenantEntity(String id, String userId, String tenantId) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
