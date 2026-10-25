package com.nocobase.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * 工作流版本快照 (US-410)。
 *
 * <p>每次发布 (publish) 时保存一份完整定义快照，支持版本列表与一键回滚。
 */
@Entity
@Table(name = "workflow_version")
public class WorkflowVersionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "version", nullable = false)
    private Integer version;

    /** 工作流定义快照 JSON：{name,title,description,trigger,nodes,edges} */
    @Column(name = "definition_json", nullable = false, columnDefinition = "TEXT")
    private String definitionJson;

    @Column(name = "published_by", nullable = false)
    private UUID publishedBy;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    public WorkflowVersionEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public UUID getPublishedBy() { return publishedBy; }
    public void setPublishedBy(UUID publishedBy) { this.publishedBy = publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}
