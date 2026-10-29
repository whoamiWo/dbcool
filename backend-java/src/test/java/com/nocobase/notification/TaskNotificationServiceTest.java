package com.nocobase.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 任务状态变更通知：只在「状态真的变了 + 有 assignee」时才发通知。 */
class TaskNotificationServiceTest {

    private NotificationService notificationService;
    private TaskNotificationService service;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        service = new TaskNotificationService(notificationService);
    }

    private TaskStatusChangedEvent event(String oldStatus, String newStatus, String assigneeId) {
        return new TaskStatusChangedEvent("task-1", "修复登录缺陷", oldStatus, newStatus,
                assigneeId, "p1", "Alpha 项目", "t1");
    }

    @Test
    @DisplayName("状态未变更 → 不触发通知")
    void statusUnchanged_skips() {
        service.handleTaskStatusChanged(event("DONE", "DONE", "u1"));

        verify(notificationService, never()).fire(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("未指派 assignee → 不触发通知")
    void noAssignee_skips() {
        service.handleTaskStatusChanged(event("TODO", "DONE", "  "));

        verify(notificationService, never()).fire(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("状态变更且有 assignee → 携带任务上下文触发通知")
    void statusChanged_fires() {
        when(notificationService.fire(eq("t1"), eq("task.status.changed"), eq("u1"), any()))
                .thenReturn(List.of());

        service.handleTaskStatusChanged(event("TODO", "DONE", "u1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationService).fire(eq("t1"), eq("task.status.changed"), eq("u1"), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("taskId", "task-1")
                .containsEntry("taskTitle", "修复登录缺陷")
                .containsEntry("oldStatus", "TODO")
                .containsEntry("newStatus", "DONE")
                .containsEntry("projectName", "Alpha 项目");
    }

    @Test
    @DisplayName("oldStatus 为 null（新建任务）→ 视为变更并触发")
    void nullOldStatus_fires() {
        when(notificationService.fire(anyString(), anyString(), anyString(), any())).thenReturn(List.of());

        service.handleTaskStatusChanged(event(null, "TODO", "u1"));

        verify(notificationService).fire(eq("t1"), eq("task.status.changed"), eq("u1"), any());
    }

    @Test
    @DisplayName("无可用通知渠道（空结果）→ 正常返回不抛异常")
    void emptyResults_noException() {
        when(notificationService.fire(anyString(), anyString(), anyString(), any())).thenReturn(List.of());

        service.handleTaskStatusChanged(event("TODO", "IN_PROGRESS", "u1"));

        verify(notificationService).fire(anyString(), anyString(), anyString(), any());
    }
}
