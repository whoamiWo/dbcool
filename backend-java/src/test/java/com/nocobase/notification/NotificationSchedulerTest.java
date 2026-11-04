package com.nocobase.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.project.ProjectTaskEntity;
import com.nocobase.project.ProjectTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** E3：DUE_SOON 定时提醒（只提醒有处理人的任务，异常隔离）。 */
class NotificationSchedulerTest {

    private ProjectTaskRepository taskRepository;
    private NotifyDispatcher dispatcher;
    private NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        taskRepository = mock(ProjectTaskRepository.class);
        dispatcher = mock(NotifyDispatcher.class);
        scheduler = new NotificationScheduler(taskRepository, dispatcher);
    }

    private ProjectTaskEntity task(UUID assignee, String tenant) {
        ProjectTaskEntity t = new ProjectTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTitle("上线检查");
        t.setAssigneeId(assignee);
        t.setTenantId(tenant);
        t.setEndDate(Instant.now().plusSeconds(3600));
        return t;
    }

    @Test
    @DisplayName("到期任务 → 通知处理人，digest_key 含日期")
    void remindDueSoon_notifiesAssignee() {
        ProjectTaskEntity t = task(UUID.randomUUID(), "t1");
        when(taskRepository.findByEndDateBetweenAndStatusNot(any(), any(), eq("DONE")))
                .thenReturn(List.of(t));

        scheduler.remindDueSoon();

        verify(dispatcher).notifyOne(eq(NotifyDispatcher.DUE_SOON), eq(t.getAssigneeId()),
                org.mockito.ArgumentMatchers.argThat(p ->
                        p.digestKey() != null && p.digestKey().startsWith("due-soon:")));
    }

    @Test
    @DisplayName("无处理人的任务 → 跳过")
    void remindDueSoon_noAssignee_skipped() {
        when(taskRepository.findByEndDateBetweenAndStatusNot(any(), any(), eq("DONE")))
                .thenReturn(List.of(task(null, "t1")));

        scheduler.remindDueSoon();

        verify(dispatcher, never()).notifyOne(any(), any(), any());
    }

    @Test
    @DisplayName("查询异常 → 不抛出（调度任务不能崩）")
    void remindDueSoon_queryError_swallowed() {
        when(taskRepository.findByEndDateBetweenAndStatusNot(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        scheduler.remindDueSoon();

        verify(dispatcher, never()).notifyOne(any(), any(), any());
    }

    @Test
    @DisplayName("单条通知异常 → 继续处理下一条")
    void remindDueSoon_singleFailureDoesNotStopOthers() {
        ProjectTaskEntity bad = task(UUID.randomUUID(), "t1");
        ProjectTaskEntity good = task(UUID.randomUUID(), "t1");
        when(taskRepository.findByEndDateBetweenAndStatusNot(any(), any(), eq("DONE")))
                .thenReturn(List.of(bad, good));
        when(dispatcher.notifyOne(any(), eq(bad.getAssigneeId()), any()))
                .thenThrow(new RuntimeException("boom"));

        scheduler.remindDueSoon();

        verify(dispatcher).notifyOne(any(), eq(good.getAssigneeId()), any());
    }
}
