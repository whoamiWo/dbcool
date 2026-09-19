package com.nocobase.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_tasks")
public class WorkflowTaskEntity {

    public enum Status { PENDING, APPROVED, REJECTED, COMPLETED }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;

    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;

    @Column(name = "assignee")
    private UUID assignee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "comment")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "title", length = 256)
    private String title;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public WorkflowTaskEntity() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getInstanceId() { return instanceId; }
    public void setInstanceId(UUID instanceId) { this.instanceId = instanceId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public UUID getAssignee() { return assignee; }
    public void setAssignee(UUID assignee) { this.assignee = assignee; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
