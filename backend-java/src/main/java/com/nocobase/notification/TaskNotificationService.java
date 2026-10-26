package com.nocobase.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.*;

/**
 * 任务状态变更通知服务。
 * 监听 TaskStatusChangedEvent，触发站内消息 + 邮件通知。
 */
@Service
public class TaskNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TaskNotificationService.class);

    private final NotificationService notificationService;

    public TaskNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 监听任务状态变更事件。
     * 仅当 oldStatus -> newStatus 变更时触发。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTaskStatusChanged(TaskStatusChangedEvent event) {
        if (event.getOldStatus() != null && event.getOldStatus().equals(event.getNewStatus())) {
            return; // 状态未变
        }
        String tenantId = event.getTenantId();
        String assigneeId = event.getAssigneeId();
        String taskId = event.getTaskId();
        String taskTitle = event.getTaskTitle();
        String oldStatus = event.getOldStatus();
        String newStatus = event.getNewStatus();

        if (assigneeId == null || assigneeId.isBlank()) {
            log.debug("[TaskNotification] 任务 {} 未指派 assignee，跳过状态变更通知", taskId);
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskId);
        payload.put("taskTitle", taskTitle);
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        payload.put("projectName", event.getProjectName());
        payload.put("timestamp", new Date().toString());

        // 触发站内消息 + 邮件（如果配置了对应 channel）
        List<NotificationDispatcher.SendResult> results = notificationService.fire(
                tenantId, "task.status.changed", assigneeId, payload
        );
        log.info("[TaskNotification] 任务状态变更通知 sent={}, task={}",
                results.stream().map(NotificationDispatcher.SendResult::success).reduce(Boolean::logicalAnd).orElse(false),
                taskId);
    }
}