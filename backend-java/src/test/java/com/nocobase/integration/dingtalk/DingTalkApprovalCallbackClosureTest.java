package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wave4 待办池 #10：钉钉审批回调业务闭环。
 *
 * 覆盖：绑定反查 → 任务按 agree/reject 标记 → 实例状态推进；未绑定时明确返回 false。
 */
class DingTalkApprovalCallbackClosureTest {

    private DingTalkApprovalService service;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        service = new DingTalkApprovalService(mock(DingTalkAppService.class),
                instanceRepository, taskRepository);
    }

    private WorkflowInstanceEntity instance() {
        WorkflowInstanceEntity i = new WorkflowInstanceEntity();
        i.setId(UUID.randomUUID());
        i.setTenantId("tenant_default");
        i.setWorkflowId(UUID.randomUUID());
        i.setStatus(WorkflowInstanceEntity.Status.PENDING);
        i.setTriggerDataJson("{\"dingtalkInstanceId\":\"dt-inst-1\"}");
        return i;
    }

    private WorkflowTaskEntity pendingTask(UUID instanceId) {
        WorkflowTaskEntity t = new WorkflowTaskEntity();
        t.setId(UUID.randomUUID());
        t.setInstanceId(instanceId);
        t.setTenantId("tenant_default");
        t.setStatus(WorkflowTaskEntity.Status.PENDING);
        return t;
    }

    @Test
    @DisplayName("回调 agree：PENDING 任务置 APPROVED，实例置 COMPLETED")
    void callback_agree_marksTaskApprovedAndCompletesInstance() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        when(instanceRepository.findByTriggerDataJsonContaining("dt-inst-1")).thenReturn(List.of(instance));
        when(taskRepository.findByInstanceId(instance.getId())).thenReturn(List.of(task));

        boolean handled = service.handleApprovalCallback("dt-inst-1", "agree");

        assertThat(handled).isTrue();
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskEntity.Status.APPROVED);
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.COMPLETED);
        verify(taskRepository).save(task);
        verify(instanceRepository).save(instance);
    }

    @Test
    @DisplayName("回调 reject：任务置 REJECTED，实例置 FAILED 并带原因")
    void callback_reject_marksTaskRejectedAndFailsInstance() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity task = pendingTask(instance.getId());
        when(instanceRepository.findByTriggerDataJsonContaining("dt-inst-1")).thenReturn(List.of(instance));
        when(taskRepository.findByInstanceId(instance.getId())).thenReturn(List.of(task));

        boolean handled = service.handleApprovalCallback("dt-inst-1", "reject");

        assertThat(handled).isTrue();
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskEntity.Status.REJECTED);
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.FAILED);
        assertThat(instance.getErrorMessage()).contains("reject");
    }

    @Test
    @DisplayName("会签：多个 PENDING 任务全部按结果标记")
    void callback_marksAllPendingTasks() {
        WorkflowInstanceEntity instance = instance();
        WorkflowTaskEntity t1 = pendingTask(instance.getId());
        WorkflowTaskEntity t2 = pendingTask(instance.getId());
        when(instanceRepository.findByTriggerDataJsonContaining("dt-inst-1")).thenReturn(List.of(instance));
        when(taskRepository.findByInstanceId(instance.getId())).thenReturn(List.of(t1, t2));

        service.handleApprovalCallback("dt-inst-1", "agree");

        assertThat(t1.getStatus()).isEqualTo(WorkflowTaskEntity.Status.APPROVED);
        assertThat(t2.getStatus()).isEqualTo(WorkflowTaskEntity.Status.APPROVED);
    }

    @Test
    @DisplayName("无绑定：返回 false 且不误改任何实例")
    void callback_withoutBinding_returnsFalse() {
        when(instanceRepository.findByTriggerDataJsonContaining(any())).thenReturn(List.of());

        boolean handled = service.handleApprovalCallback("dt-unknown", "agree");

        assertThat(handled).isFalse();
        verify(taskRepository, org.mockito.Mockito.never()).save(any());
    }
}
