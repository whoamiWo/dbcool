package com.nocobase.form;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 表单元数据(Week 8 Epic 2).
 *
 * <p>layout: 字段顺序 + 分组(简化版 Week 8 只存字段数组)
 * <pre>
 * layout = [
 *   { field: "name", span: 24 },          // 全宽
 *   { field: "age", span: 12 },           // 半宽
 *   { field: "email", span: 12 }
 * ]
 * </pre>
 *
 * <p>rules: 显隐 + 校验 + 提交动作
 * <pre>
 * rules = {
 *   visibility: { "email": { when: "type", op: "eq", value: "business" } },
 *   validation: { "age": { min: 0, max: 150 } },
 *   submit: { action: "redirect", url: "/thanks" }
 * }
 * </pre>
 */
@Entity
@Table(name = "forms")
public class FormEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "collection_name", nullable = false, length = 64)
    private String collectionName;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout", columnDefinition = "jsonb", nullable = false)
    private String layoutJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules", columnDefinition = "jsonb", nullable = false)
    private String rulesJson = "{}";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public FormEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getLayoutJson() { return layoutJson; }
    public void setLayoutJson(String layoutJson) { this.layoutJson = layoutJson; }

    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
