package com.nocobase.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.notification.NotifyDispatcher;
import com.nocobase.workflow.WorkflowEngine;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * E2 审批服务：提交前校验（A-7 配套）、状态投影、撤回、模板向导。
 */
class ApprovalServiceTest {

    private ApprovalTemplateRepository templateRepository;
    private ApprovalRequestRepository requestRepository;
    private ApprovalCcRepository ccRepository;
    private WorkflowRepository workflowRepository;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowEngine engine;
    private UserRepository userRepository;
    private ApprovalService service;

    private static final String TENANT = "tenant_a";
    private final UUID applicant = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        templateRepository = mock(ApprovalTemplateRepository.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        ccRepository = mock(ApprovalCcRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        engine = mock(WorkflowEngine.class);
        userRepository = mock(UserRepository.class);
        service = new ApprovalService(templateRepository, requestRepository, ccRepository,
                workflowRepository, instanceRepository, taskRepository, engine, userRepository,
                mock(com.nocobase.integration.dingtalk.DingTalkApprovalService.class),
                mock(NotifyDispatcher.class),
                mock(com.nocobase.search.UnifiedSearchService.class),
                false);

        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findByInstanceIdAndStatus(any(), any())).thenReturn(List.of());
        when(taskRepository.findByInstanceId(any())).thenReturn(List.of());
        when(ccRepository.findByRequestId(any())).thenReturn(List.of());
    }

    private ApprovalTemplateEntity template(String approverType) {
        ApprovalTemplateEntity t = new ApprovalTemplateEntity();
        t.setId(templateId);
        t.setTenantId(TENANT);
        t.setName("请假");
        t.setWorkflowId(workflowId);
        t.setApproverType(approverType);
        t.setEnabled(true);
        return t;
    }

    private WorkflowEntity workflowWithApproval(String approverType) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(workflowId);
        w.setTenantId(TENANT);
        w.setName("wf");
        w.setCollectionName("approval_request");
        w.setNodesJson("[{\"id\":\"a1\",\"type\":\"APPROVAL\",\"config\":{\"approverType\":\""
                + approverType + "\"}}]");
        w.setEdgesJson("[]");
        w.setCreatedBy(applicant);
        return w;
    }

    private UserEntity user(UUID id, UUID managerId) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setTenantId(TENANT);
        u.setDisplayName("张三");
        u.setManagerId(managerId);
        u.setEnabled(true);
        return u;
    }

    @Test
    @DisplayName("MANAGER 审批但申请人无上级 → 400，且不创建工作流实例")
    void submit_managerApprovalWithoutManager_rejected() {
        when(templateRepository.findByIdAndTenantId(templateId, TENANT))
                .thenReturn(Optional.of(template("MANAGER")));
        when(workflowRepository.findByIdAndTenantId(workflowId, TENANT))
                .thenReturn(Optional.of(workflowWithApproval("MANAGER")));
        when(userRepository.findByIdAndTenantId(applicant, TENANT))
                .thenReturn(Optional.of(user(applicant, null)));

        assertThatThrownBy(() -> service.submit(TENANT, applicant, templateId, Map.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("未配置上级");
        verify(instanceRepository, never()).save(any());
        verify(engine, never()).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("审批节点未配置审批人 → 400（不进入工作流，避免 A-7 幽灵任务）")
    void submit_approvalNodeWithoutAssignee_rejected() {
        WorkflowEntity w = workflowWithApproval("USER");
        when(templateRepository.findByIdAndTenantId(templateId, TENANT))
                .thenReturn(Optional.of(template("USER")));
        when(workflowRepository.findByIdAndTenantId(workflowId, TENANT)).thenReturn(Optional.of(w));
        when(userRepository.findByIdAndTenantId(applicant, TENANT))
                .thenReturn(Optional.of(user(applicant, null)));

        assertThatThrownBy(() -> service.submit(TENANT, applicant, templateId, Map.of(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("未配置审批人");
        verify(instanceRepository, never()).save(any());
    }

    @Test
    @DisplayName("提交成功 → 创建实例并执行引擎，单据为 PENDING")
    void submit_success_startsWorkflow() {
        WorkflowEntity w = workflowWithApproval("USER");
        w.setNodesJson("[{\"id\":\"a1\",\"type\":\"APPROVAL\",\"config\":{\"assignee\":\""
                + UUID.randomUUID() + "\"}}]");
        when(templateRepository.findByIdAndTenantId(templateId, TENANT))
                .thenReturn(Optional.of(template("USER")));
        when(workflowRepository.findByIdAndTenantId(workflowId, TENANT)).thenReturn(Optional.of(w));
        when(userRepository.findByIdAndTenantId(applicant, TENANT))
                .thenReturn(Optional.of(user(applicant, null)));
        when(engine.executeFrom(any(), any(), anyInt(), any()))
                .thenReturn(WorkflowEngine.NodeResult.NEEDS_APPROVAL);

        Map<String, Object> data = service.submit(TENANT, applicant, templateId,
                Map.of("title", "请假 3 天"), null);

        assertThat(data.get("status")).isEqualTo("PENDING");
        verify(instanceRepository).save(any(WorkflowInstanceEntity.class));
        verify(engine).executeFrom(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("实例 COMPLETED → 单据 APPROVED；FAILED → REJECTED")
    void syncStatus_projectsInstanceStatus() {
        ApprovalRequestEntity req = new ApprovalRequestEntity();
        req.setId(UUID.randomUUID());
        req.setTenantId(TENANT);
        req.setTemplateId(templateId);
        req.setApplicantId(applicant);
        req.setInstanceId(UUID.randomUUID());
        req.setStatus("PENDING");

        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(req.getInstanceId());
        instance.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
        assertThat(service.syncStatus(req, instance).getStatus()).isEqualTo("APPROVED");

        instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
        assertThat(service.syncStatus(req, instance).getStatus()).isEqualTo("REJECTED");

        instance.setStatus(WorkflowInstanceEntity.Status.TERMINATED);
        assertThat(service.syncStatus(req, instance).getStatus()).isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("撤回：实例 TERMINATED + 未完成任务 CANCELED")
    void cancel_terminatesInstanceAndTasks() {
        UUID requestId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        ApprovalRequestEntity req = new ApprovalRequestEntity();
        req.setId(requestId);
        req.setTenantId(TENANT);
        req.setTemplateId(templateId);
        req.setApplicantId(applicant);
        req.setInstanceId(instanceId);
        req.setStatus("PENDING");
        when(requestRepository.findByIdAndTenantId(requestId, TENANT)).thenReturn(Optional.of(req));

        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        when(instanceRepository.findById(instanceId)).thenReturn(Optional.of(instance));

        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setId(UUID.randomUUID());
        when(taskRepository.findByInstanceIdAndStatus(instanceId, WorkflowTaskEntity.Status.PENDING))
                .thenReturn(List.of(task));

        Map<String, Object> detail = service.cancel(TENANT, requestId, applicant);

        assertThat(detail.get("status")).isEqualTo("CANCELED");
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.TERMINATED);
        assertThat(task.getStatus()).isEqualTo(WorkflowTaskEntity.Status.CANCELED);
    }

    @Test
    @DisplayName("非申请人撤回 → 403")
    void cancel_byOtherUser_forbidden() {
        ApprovalRequestEntity req = new ApprovalRequestEntity();
        req.setId(UUID.randomUUID());
        req.setTenantId(TENANT);
        req.setApplicantId(applicant);
        when(requestRepository.findByIdAndTenantId(req.getId(), TENANT)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.cancel(TENANT, req.getId(), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("模板向导：N 级审批生成 N 个 APPROVAL 节点 + N-1 条边，workflow_id 回写模板")
    void createFromPreset_buildsWorkflowAndTemplate() {
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String assignee = UUID.randomUUID().toString();
        ApprovalTemplateEntity t = service.createFromPreset(TENANT, applicant, "报销",
                "财务", 3, "USER", assignee);

        assertThat(t.getWorkflowId()).isNotNull();
        verify(workflowRepository).save(any());
        org.mockito.ArgumentCaptor<WorkflowEntity> captor =
                org.mockito.ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(captor.capture());
        WorkflowEntity w = captor.getValue();
        assertThat(w.getNodesJson()).contains("approve-1", "approve-2", "approve-3");
        assertThat(w.getEdgesJson()).contains("approve-1", "approve-2");
        assertThat(w.getTriggerJson()).contains("FORM_SUBMIT");
    }

    @Test
    @DisplayName("ROLE 模板必须提供 roleId")
    void createFromPreset_roleWithoutRoleId_rejected() {
        assertThatThrownBy(() -> service.createFromPreset(TENANT, applicant, "报销",
                "财务", 1, "ROLE", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("roleId");
    }
}
