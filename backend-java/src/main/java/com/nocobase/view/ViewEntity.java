package com.nocobase.view;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 视图实体(Week 9 Epic 3).
 *
 * <p>type: table / kanban / detail
 * <p>config JSON 示例:
 * <ul>
 *   <li>table: {"columns": [{"field": "name", "width": 200}, ...], "pageSize": 20, "sort": [...]}</li>
 *   <li>kanban: {"groupBy": "status", "cardTitleField": "title"}</li>
 *   <li>detail: {"layout": "vertical"}</li>
 * </ul>
 */
@Entity
@Table(name = "views")
public class ViewEntity {

    public enum Type {
        TABLE, KANBAN, DETAIL, GALLERY, CALENDAR
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "collection_name", nullable = false, length = 64)
    private String collectionName;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "title", length = 128)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private Type type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    private String configJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shared_with", columnDefinition = "jsonb", nullable = false)
    private String sharedWithJson = "[]";

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public ViewEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public String getSharedWithJson() { return sharedWithJson; }
    public void setSharedWithJson(String sharedWithJson) { this.sharedWithJson = sharedWithJson; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
