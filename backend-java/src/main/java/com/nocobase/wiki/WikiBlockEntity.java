package com.nocobase.wiki;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Wiki Block 块实体 — 对标 Notion Block 模型。
 *
 * <p>页面内容由整块 TEXT 改为 Block 树，支持：
 * <ul>
 *   <li>类型: paragraph/heading/bullet/numbered/code/quote/image/embed/todo/divider 等</li>
 *   <li>父子嵌套 (parent_id 自引用)</li>
 *   <li>排序 (sort_order)</li>
 *   <li>内容存 JSONB (灵活扩展)</li>
 * </ul>
 */
@Entity
@Table(name = "wiki_block")
public class WikiBlockEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "page_id", nullable = false)
    private UUID pageId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "content", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String contentJson = "{}";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WikiBlockEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPageId() { return pageId; }
    public void setPageId(UUID pageId) { this.pageId = pageId; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}