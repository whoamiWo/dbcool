package com.nocobase.workflow;

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

@Entity
@Table(name = "workflow_instances")
public class WorkflowInstanceEntity {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_data", columnDefinition = "jsonb", nullable = false)
    private String triggerDataJson = "{}";

    @Column(name = "record_id", length = 64)
    private String recordId;

    @Column(name = "current_node_index", nullable = false)
    private int currentNodeIndex = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "channel_id")
    private UUID channelId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    public WorkflowInstanceEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getWorkflowId() { return workflowId; }
    public void setWorkflowId(UUID workflowId) { this.workflowId = workflowId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getTriggerDataJson() { return triggerDataJson; }
    public void setTriggerDataJson(String triggerDataJson) { this.triggerDataJson = triggerDataJson; }
    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }
    public int getCurrentNodeIndex() { return currentNodeIndex; }
    public void setCurrentNodeIndex(int currentNodeIndex) { this.currentNodeIndex = currentNodeIndex; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
