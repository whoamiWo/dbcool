package com.nocobase.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 多租户元数据实体(Week 41 D6 Step G1 — ADR-007 实现).
 *
 * <p>按 ADR-007:`shared` schema 存用户、租户元数据、套餐信息。
 * 当前实现未启用 schema 路由(推迟到 D6 Step G2),实体落在 public
 * (与现有 collection_meta 等同),但业务代码已通过此 entity + repository
 * 抽象租户管理,Step G2 切换 schema 路由时无需改业务代码。
 *
 * <p>字段:
 * <ul>
 *   <li>id — 租户 ID(字符串,如 "tenant_default" / "tenant_acme"),用作 schema 名</li>
 *   <li>name — 显示名</li>
 *   <li>slug — URL 友好短标识</li>
 *   <li>status — 启用/禁用</li>
 *   <li>schemaName — 实际 schema 名(默认 = id)</li>
 * </ul>
 */
@Entity
@Table(name = "tenant")
public class TenantEntity {

    public enum Status { ACTIVE, DISABLED }

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 64)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "schema_name", nullable = false, length = 64)
    private String schemaName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public TenantEntity() {}

    public TenantEntity(String id, String name, String slug) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.schemaName = id; // 默认 schema = id
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
