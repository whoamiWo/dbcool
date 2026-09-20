package com.nocobase.wiki;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Wiki 双向链接关系实体 — source_page → target_page。
 *
 * <p>替换现有文本 [[slug]] 解析，改为关系表存储，支持：
 * <ul>
 *   <li>高效反向链接查询 (target_page_id 索引)</li>
 *   <li>目标页删除后仍保留 target_slug 兼容显示</li>
 *   <li>租户隔离</li>
 * </ul>
 */
@Entity
@Table(name = "wiki_backlink")
public class WikiBacklinkEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "source_page_id", nullable = false)
    private UUID sourcePageId;

    @Column(name = "target_page_id")
    private UUID targetPageId;

    @Column(name = "target_slug", length = 256)
    private String targetSlug;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public WikiBacklinkEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSourcePageId() { return sourcePageId; }
    public void setSourcePageId(UUID sourcePageId) { this.sourcePageId = sourcePageId; }

    public UUID getTargetPageId() { return targetPageId; }
    public void setTargetPageId(UUID targetPageId) { this.targetPageId = targetPageId; }

    public String getTargetSlug() { return targetSlug; }
    public void setTargetSlug(String targetSlug) { this.targetSlug = targetSlug; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}