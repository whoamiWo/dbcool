package com.nocobase.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 组织架构部门节点（E1）。
 *
 * <p>组织与人员的 SSOT 是外部平台（钉钉/企微/飞书，决策 D1），本地表是只读镜像；
 * {@code source='LOCAL'} 的子树允许本地编辑（未接外部平台的企业可用）。
 *
 * <p>{@code path} 是物化路径，形式 <code>/根id/子id/叶id/</code>。用 id 而不是名称，
 * 避免改部门名导致整棵子树路径重写；子树查询用 {@code path LIKE '/root/child/%'}。
 */
@Entity
@Table(name = "org_unit")
public class OrgUnitEntity {

    public static final String SOURCE_LOCAL = "LOCAL";

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code")
    private String code;

    @Column(name = "path", nullable = false)
    private String path = "/";

    @Column(name = "sort", nullable = false)
    private int sort;

    @Column(name = "source", nullable = false)
    private String source = SOURCE_LOCAL;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public OrgUnitEntity() {}

    public boolean isLocal() {
        return SOURCE_LOCAL.equals(source);
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public int getSort() { return sort; }
    public void setSort(int sort) { this.sort = sort; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
