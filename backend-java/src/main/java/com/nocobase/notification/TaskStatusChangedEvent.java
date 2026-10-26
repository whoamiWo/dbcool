package com.nocobase.notification;

import java.time.Instant;

/**
 * 任务状态变更事件。
 * 由 ProjectService 在任务状态发生变更时发布。
 */
public class TaskStatusChangedEvent {
    private final String taskId;
    private final String taskTitle;
    private final String oldStatus;
    private final String newStatus;
    private final String assigneeId;
    private final String projectId;
    private final String projectName;
    private final String tenantId;
    private final Instant occurredAt;

    public TaskStatusChangedEvent(String taskId, String taskTitle, String oldStatus, String newStatus,
                                   String assigneeId, String projectId, String projectName,
                                   String tenantId) {
        this.taskId = taskId;
        this.taskTitle = taskTitle;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.assigneeId = assigneeId;
        this.projectId = projectId;
        this.projectName = projectName;
        this.tenantId = tenantId;
        this.occurredAt = Instant.now();
    }

    public String getTaskId() { return taskId; }
    public String getTaskTitle() { return taskTitle; }
    public String getOldStatus() { return oldStatus; }
    public String getNewStatus() { return newStatus; }
    public String getAssigneeId() { return assigneeId; }
    public String getProjectId() { return projectId; }
    public String getProjectName() { return projectName; }
    public String getTenantId() { return tenantId; }
    public Instant getOccurredAt() { return occurredAt; }
}