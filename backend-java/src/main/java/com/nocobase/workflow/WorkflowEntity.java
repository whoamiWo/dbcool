package com.nocobase.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 工作流定义(US-401).
 *
 * <p>trigger: {type, config}
 * <p>nodes: [{id, type, config}]
 */
@Entity
@Table(name = "workflows")
public class WorkflowEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "title", length = 128)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "collection_name", nullable = false, length = 64)
    private String collectionName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger", columnDefinition = "jsonb", nullable = false)
    private String triggerJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "nodes", columnDefinition = "jsonb", nullable = false)
    private String nodesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edges", columnDefinition = "jsonb", nullable = false)
    private String edgesJson = "[]";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    public WorkflowEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCollectionName() { return collectionName; }
    public void setCollectionName(String collectionName) { this.collectionName = collectionName; }
    public String getTriggerJson() { return triggerJson; }
    public void setTriggerJson(String triggerJson) { this.triggerJson = triggerJson; }
    public String getNodesJson() { return nodesJson; }
    public void setNodesJson(String nodesJson) { this.nodesJson = nodesJson; }
    public String getEdgesJson() { return edgesJson; }
    public void setEdgesJson(String edgesJson) { this.edgesJson = edgesJson; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
