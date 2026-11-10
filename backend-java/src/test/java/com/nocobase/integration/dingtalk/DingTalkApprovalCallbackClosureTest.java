package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.approval.ApprovalCcRepository;
import com.nocobase.approval.ApprovalRequestEntity;
import com.nocobase.approval.ApprovalRequestRepository;
import com.nocobase.workflow.WorkflowEngine;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import com.nocobase.workflow.WorkflowTaskService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DingTalkApprovalCallbackClosureTest {
    private static final String TENANT = "tenant_a";
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowRepository workflowRepository;
    private ApprovalRequestRepository requestRepository;
    private WorkflowEngine engine;
    private WorkflowTaskService taskService;
    private ApprovalRequestEntity request;
    private WorkflowInstanceEntity instance;
    private WorkflowEntity workflow;

    @BeforeEach
    void setUp() {
        instanceRepository = mock(WorkflowInstanceRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        requestRepository = mock(ApprovalRequestRepository.class);
        engine = mock(WorkflowEngine.class);
        taskService = new WorkflowTaskService(mock(WorkflowTaskRepository.class), instanceRepository,
                workflowRepository, engine, mock(com.nocobase.audit.AuditService.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), requestRepository,
                mock(ApprovalCcRepository.class), mock(com.nocobase.notification.NotifyDispatcher.class));
        org.springframework.test.util.ReflectionTestUtils.setField(taskService, "approvalService",
                mock(com.nocobase.approval.ApprovalService.class));
        request = new ApprovalRequestEntity();
        request.setId(UUID.randomUUID());
        request.setTenantId(TENANT);
        request.setApplicantId(UUID.randomUUID());
        request.setExternalPushStatus("PUSHED");
        request.setStatus("PENDING");
        instance = new WorkflowInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setTenantId(TENANT);
        instance.setWorkflowId(UUID.randomUUID());
        instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
        request.setInstanceId(instance.getId());
        workflow = new WorkflowEntity();
        workflow.setId(instance.getWorkflowId());
        workflow.setTenantId(TENANT);
        workflow.setNodesJson("[{\"id\":\"external\",\"type\":\"DINGTALK_APPROVAL\"}]");
        workflow.setEdgesJson("[]");
        when(requestRepository.findByIdAndTenantId(request.getId(), TENANT)).thenReturn(Optional.of(request));
        when(requestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(instanceRepository.findLockedByIdAndTenantId(instance.getId(), TENANT)).thenReturn(Optional.of(instance));
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.findByIdAndTenantId(workflow.getId(), TENANT)).thenReturn(Optional.of(workflow));
    }

    @Test
    void agree_completesWithoutCreatingLocalHumanTask() {
        assertThat(taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree")).isTrue();
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.COMPLETED);
        assertThat(request.getStatus()).isEqualTo("APPROVED");
        verify(engine, never()).executeFrom(any(), any(), any(Integer.class), any());
    }

    @Test
    void refuse_rejectsButDoesNotTurnPushIntoFailure() {
        assertThat(taskService.approveByExternalBinding(TENANT, request.getId(), false, "refuse")).isTrue();
        assertThat(instance.getStatus()).isEqualTo(WorkflowInstanceEntity.Status.FAILED);
        assertThat(request.getStatus()).isEqualTo("REJECTED");
        assertThat(request.getExternalPushStatus()).isEqualTo("PUSHED");
    }

    @Test
    void localApprovalNode_cannotBeAdvancedExternally() {
        workflow.setNodesJson("[{\"id\":\"local\",\"type\":\"APPROVAL\"}]");
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("外部审批节点");
        verify(instanceRepository, never()).save(any());
    }

    @Test
    void notPushedOrMissingInstanceCannotAdvance() {
        request.setExternalPushStatus("FAILED");
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class);
        request.setExternalPushStatus("PUSHED");
        when(instanceRepository.findLockedByIdAndTenantId(instance.getId(), TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void missingBindingOrTerminalRequest_rejected() {
        when(requestRepository.findByIdAndTenantId(request.getId(), TENANT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class);
        when(requestRepository.findByIdAndTenantId(request.getId(), TENANT)).thenReturn(Optional.of(request));
        request.setStatus("APPROVED");
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("终结");
    }

    @Test
    void linearAdvanceUsesRequestIdAndSyncsPendingProjection() {
        workflow.setNodesJson("[{\"id\":\"external\",\"type\":\"DINGTALK_APPROVAL\"},{\"id\":\"local\",\"type\":\"APPROVAL\"}]");
        when(engine.executeFrom(any(), any(), any(Integer.class), any(), any()))
                .thenReturn(WorkflowEngine.NodeResult.NEEDS_APPROVAL);
        assertThat(taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree")).isTrue();
        verify(engine).executeFrom(eq(instance), any(), eq(1), any(), eq(request.getId()));
        assertThat(request.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void invalidNodeIndexOrJsonFailsClosed() {
        instance.setCurrentNodeIndex(-1);
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class);
        instance.setCurrentNodeIndex(0);
        workflow.setNodesJson("not-json");
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void nonPendingWorkflowCannotBeAdvancedExternally() {
        instance.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void terminatedInstanceMapsCancelledAndMissingWorkflowRejected() {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(taskService, "syncExternalRequest",
                request, instance);
        instance.setStatus(WorkflowInstanceEntity.Status.TERMINATED);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(taskService, "syncExternalRequest",
                request, instance);
        assertThat(request.getStatus()).isEqualTo("CANCELED");
        when(workflowRepository.findByIdAndTenantId(workflow.getId(), TENANT)).thenReturn(Optional.empty());
        instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
        assertThatThrownBy(() -> taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void nullEdgesFallBackToLinearExecution() {
        workflow.setNodesJson("[{\"id\":\"external\",\"type\":\"DINGTALK_APPROVAL\"},{\"id\":\"notice\",\"type\":\"NOTIFICATION\"}]");
        workflow.setEdgesJson(null);
        when(engine.executeFrom(any(), any(), any(Integer.class), any(), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);
        assertThat(taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree")).isTrue();
        verify(engine).executeFrom(any(), any(), eq(1), any(), eq(request.getId()));
    }

    @Test
    void graphAdvancesFromNextNodeAndPassesRequestId() {
        workflow.setNodesJson("[{\"id\":\"external\",\"type\":\"DINGTALK_APPROVAL\"},{\"id\":\"notice\",\"type\":\"NOTIFICATION\"}]");
        workflow.setEdgesJson("[{\"source\":\"external\",\"target\":\"notice\"}]");
        instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
        when(engine.executeGraphFrom(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    instance.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
                    return WorkflowEngine.NodeResult.CONTINUE;
                });
        assertThat(taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree")).isTrue();
        verify(engine).executeGraphFrom(eq(instance), any(), any(), eq("notice"), any(), eq(request.getId()));
        assertThat(request.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void downstreamFailureMapsRejectedWithoutRevivingTerminalInstance() {
        workflow.setNodesJson("[{\"id\":\"external\",\"type\":\"DINGTALK_APPROVAL\"},{\"id\":\"notice\",\"type\":\"NOTIFICATION\"}]");
        workflow.setEdgesJson("not-json");
        when(engine.executeFrom(any(), any(), any(Integer.class), any(), any()))
                .thenAnswer(inv -> {
                    instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
                    return WorkflowEngine.NodeResult.FAILED;
                });
        assertThat(taskService.approveByExternalBinding(TENANT, request.getId(), true, "agree")).isTrue();
        assertThat(request.getStatus()).isEqualTo("REJECTED");
    }

}
