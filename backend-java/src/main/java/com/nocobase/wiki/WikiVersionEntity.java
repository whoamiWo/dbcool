package com.nocobase.wiki;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 版本历史实体 — 记录文档每次修改的快照。
 *
 * <p>每次更新 WikiPage 时,自动创建 WikiVersion 记录,
 * 支持版本对比与一键回滚。
 */
@Entity
@Table(name = "wiki_version")
public class WikiVersionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "wiki_page_id", nullable = false)
    private UUID wikiPageId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "summary", length = 512)
    private String summary;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public WikiVersionEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getWikiPageId() { return wikiPageId; }
    public void setWikiPageId(UUID wikiPageId) { this.wikiPageId = wikiPageId; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}