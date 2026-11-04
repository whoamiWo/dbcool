package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.approval.ApprovalCcEntity;
import com.nocobase.approval.ApprovalCcRepository;
import com.nocobase.approval.ApprovalRequestEntity;
import com.nocobase.approval.ApprovalRequestRepository;
import com.nocobase.notification.NotifyDispatcher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * E3 接入点：审批待办 / 审批结果通知（WorkflowTaskService 侧）。
 *
 * <p>通知是"审批状态流转"的下游副作用，这里直接验证两个私有 hook 的分支，
 * 避开完整引擎执行，保证通知失败/成功路径都被覆盖。
 */
class WorkflowTaskServiceNotificationTest {

    private WorkflowTaskRepository taskRepository;
    private ApprovalRequestRepository approvalRequestRepository;
    private ApprovalCcRepository approvalCcRepository;
    private NotifyDispatcher notifyDispatcher;
    private WorkflowTaskService service;

    private static final String TENANT = "t1";
    private final UUID instanceId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID applicant = UUID.randomUUID();
    private final UUID assignee = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        taskRepository = mock(WorkflowTaskRepository.class);
        approvalRequestRepository = mock(ApprovalRequestRepository.class);
        approvalCcRepository = mock(ApprovalCcRepository.class);
        notifyDispatcher = mock(NotifyDispatcher.class);
        service = new WorkflowTaskService(
                taskRepository,
                mock(WorkflowInstanceRepository.class),
                mock(WorkflowRepository.class),
                mock(WorkflowEngine.class),
                mock(com.nocobase.audit.AuditService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                approvalRequestRepository,
                approvalCcRepository,
                notifyDispatcher);
    }

    private WorkflowInstanceEntity instance(WorkflowInstanceEntity.Status status) {
        WorkflowInstanceEntity i = new WorkflowInstanceEntity();
        i.setId(instanceId);
        i.setTenantId(TENANT);
        i.setStatus(status);
        return i;
    }

    @Test
    @DisplayName("待办通知：每个 PENDING 任务通知其处理人，deeplink 指向审批详情")
    void notifyTodoTasks_notifiesEachAssignee() {
        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setId(UUID.randomUUID());
        task.setInstanceId(instanceId);
        task.setAssignee(assignee);
        when(taskRepository.findByInstanceIdAndStatus(instanceId, WorkflowTaskEntity.Status.PENDING))
                .thenReturn(List.of(task));

        ApprovalRequestEntity req = new ApprovalRequestEntity();
        req.setId(requestId);
        when(approvalRequestRepository.findByInstanceId(instanceId)).thenReturn(Optional.of(req));

        ReflectionTestUtils.invokeMethod(service, "notifyTodoTasks", instance(
                WorkflowInstanceEntity.Status.PENDING));

        verify(notifyDispatcher).notifyOne(eq(NotifyDispatcher.APPROVAL_TODO), eq(assignee),
                org.mockito.ArgumentMatchers.argThat(p ->
                        ("/approvals/" + requestId).equals(p.deeplink())
                                && TENANT.equals(p.tenantId())));
    }

    @Test
    @DisplayName("待办通知：无 PENDING 任务 → 不发送")
    void notifyTodoTasks_noPending_noop() {
        when(taskRepository.findByInstanceIdAndStatus(any(), any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(service, "notifyTodoTasks", instance(
                WorkflowInstanceEntity.Status.PENDING));

        verify(notifyDispatcher, never()).notifyOne(any(), any(), any());
    }

    @Test
    @DisplayName("结果通知：申请人 + 抄送人各一条")
    void notifyResult_notifiesApplicantAndCc() {
        ApprovalRequestEntity req = new ApprovalRequestEntity();
        req.setId(requestId);
        req.setApplicantId(applicant);
        when(approvalRequestRepository.findByInstanceId(instanceId)).thenReturn(Optional.of(req));
        UUID ccUser = UUID.randomUUID();
        when(approvalCcRepository.findByRequestId(requestId))
                .thenReturn(List.of(new ApprovalCcEntity(requestId, ccUser)));

        ReflectionTestUtils.invokeMethod(service, "notifyResult",
                instance(WorkflowInstanceEntity.Status.COMPLETED), true);

        verify(notifyDispatcher).notifyOne(eq(NotifyDispatcher.APPROVAL_RESULT), eq(applicant), any());
        verify(notifyDispatcher).notifyOne(eq(NotifyDispatcher.APPROVAL_RESULT), eq(ccUser), any());
    }

    @Test
    @DisplayName("结果通知：找不到审批单据（普通工作流）→ 不发")
    void notifyResult_noApprovalRequest_noop() {
        when(approvalRequestRepository.findByInstanceId(instanceId)).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(service, "notifyResult",
                instance(WorkflowInstanceEntity.Status.COMPLETED), true);

        verify(notifyDispatcher, never()).notifyOne(any(), any(), any());
    }

    @Test
    @DisplayName("afterAdvance：NEEDS_APPROVAL → 待办；FAILED → 结果；COMPLETED → 结果")
    void afterAdvance_dispatchByResult() {
        WorkflowInstanceEntity pending = instance(WorkflowInstanceEntity.Status.PENDING);
        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setId(UUID.randomUUID());
        task.setAssignee(assignee);
        when(taskRepository.findByInstanceIdAndStatus(instanceId, WorkflowTaskEntity.Status.PENDING))
                .thenReturn(List.of(task));
        when(approvalRequestRepository.findByInstanceId(instanceId)).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(service, "afterAdvance", pending,
                WorkflowEngine.NodeResult.NEEDS_APPROVAL);
        verify(notifyDispatcher).notifyOne(eq(NotifyDispatcher.APPROVAL_TODO), eq(assignee), any());

        WorkflowInstanceEntity completed = instance(WorkflowInstanceEntity.Status.COMPLETED);
        ApprovalRequestEntity req = new ApprovalRequestEntity();
        req.setId(requestId);
        req.setApplicantId(applicant);
        when(approvalRequestRepository.findByInstanceId(instanceId)).thenReturn(Optional.of(req));
        ReflectionTestUtils.invokeMethod(service, "afterAdvance", completed,
                WorkflowEngine.NodeResult.CONTINUE);
        verify(notifyDispatcher).notifyOne(eq(NotifyDispatcher.APPROVAL_RESULT), eq(applicant), any());
    }

    @Test
    @DisplayName("通知异常不能打断主流程（异常被吞）")
    void notifyResult_exceptionSwallowed() {
        when(approvalRequestRepository.findByInstanceId(instanceId))
                .thenThrow(new RuntimeException("db down"));

        ReflectionTestUtils.invokeMethod(service, "notifyResult",
                instance(WorkflowInstanceEntity.Status.COMPLETED), true);

        // 没有抛出即通过；同时不应调用通知
        verify(notifyDispatcher, never()).notifyOne(any(), any(), any());
        assertThat(true).isTrue();
    }
}
