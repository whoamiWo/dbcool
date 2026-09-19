package com.nocobase.project;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * 卡片标签实体 — Trello 对标 CardLabel。
 *
 * <p>标签用于视觉区分（颜色 + 名称），挂在卡片上。
 */
@Entity
@Table(name = "card_labels")
public class CardLabelEntity {

    public enum Color { RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, PINK, GRAY }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "color", nullable = false, length = 16)
    private String color = "BLUE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CardLabelEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}