package com.nocobase.playbook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Playbook 剧本定义（折中版）。
 *
 * <p>定义源 {@code yamlSource} 实际存储节点定义 JSON（顶层数组或 {nodes, edges, checklist} 对象），
 * 运行状态由 {@link PlaybookRunEntity} 承载；执行复用 WorkflowEngine。
 * 首次运行时把定义编译为 WorkflowEntity 并缓存到 {@code workflowId}，后续运行复用，
 * 避免"每次运行 new WorkflowEntity 落库"污染 workflow 表。
 */
@Entity
@Table(name = "playbook")
public class PlaybookEntity {

    public enum Status { DRAFT, ACTIVE, ARCHIVED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /** 节点定义 JSON（含 steps/checklist 定义）。 */
    @Column(name = "yaml_source", columnDefinition = "text", nullable = false)
    private String yamlSource;

    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private java.time.Instant createdAt;

    /** 编译后的节点图(WorkflowEntity.id),首次运行时创建并缓存。 */
    @Column(name = "workflow_id")
    private UUID workflowId;

    public PlaybookEntity() {}

    // getters / setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getYamlSource() { return yamlSource; }
    public void setYamlSource(String yamlSource) { this.yamlSource = yamlSource; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
}