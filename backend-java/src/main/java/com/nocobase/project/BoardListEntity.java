package com.nocobase.project;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 看板列实体 — Trello 对标 BoardList。
 *
 * <p>一个项目可有多个看板列（如 TODO / IN_PROGRESS / DONE），
 * 每列包含多个卡片。列内卡片通过 sort_order 排序。
 */
@Entity
@Table(name = "board_lists")
public class BoardListEntity {

    public enum Type { TODO, IN_PROGRESS, DONE, BLOCKED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "type", nullable = false, length = 32)
    private String type = "TODO";

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "wip_limit")
    private Integer wipLimit;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public BoardListEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Integer getWipLimit() { return wipLimit; }
    public void setWipLimit(Integer wipLimit) { this.wipLimit = wipLimit; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}