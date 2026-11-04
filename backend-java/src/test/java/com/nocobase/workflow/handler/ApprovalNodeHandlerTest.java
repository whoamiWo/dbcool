package com.nocobase.workflow.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nocobase.tenant.TenantContext;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;

class ApprovalNodeHandlerTest {

    private final WorkflowInstanceRepository instanceRepository = mock(WorkflowInstanceRepository.class);
    private final WorkflowTaskRepository taskRepository = mock(WorkflowTaskRepository.class);
    private final com.nocobase.auth.UserRepository userRepository = mock(com.nocobase.auth.UserRepository.class);
    private final com.nocobase.auth.UserRoleRepository userRoleRepository = mock(com.nocobase.auth.UserRoleRepository.class);
    private final com.nocobase.auth.RoleRepository roleRepository = mock(com.nocobase.auth.RoleRepository.class);
    private final ApprovalNodeHandler handler = new ApprovalNodeHandler(
            instanceRepository, taskRepository, userRepository, userRoleRepository, roleRepository);

    @Test
    void singleAssignee_createsOneTask() {
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId("tenant_1");
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config", Map.of("assignee", assignee.toString())));
        when(taskRepository.save(any(WorkflowTaskEntity.class))).thenAnswer(i -> i.getArguments()[0]);
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.NEEDS_APPROVAL, outcome);
        verify(taskRepository).save(argThat(task ->
            task.getNodeType().equals("APPROVAL") &&
            task.getAssignee().equals(assignee) &&
            task.getStatus() == WorkflowTaskEntity.Status.PENDING &&
            task.getCreatedAt() != null
        ));
        verify(instanceRepository).save(argThat(inst ->
            inst.getStatus() == WorkflowInstanceEntity.Status.PENDING
        ));
    }

    @Test
    void multipleAssignees_createsMultipleTasks() {
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        List<UUID> assignees = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId("tenant_1");
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config", Map.of("assignees", assignees)));
        when(taskRepository.save(any(WorkflowTaskEntity.class))).thenAnswer(i -> i.getArguments()[0]);
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.NEEDS_APPROVAL, outcome);
        verify(taskRepository, times(3)).save(any(WorkflowTaskEntity.class));
    }

    @Test
    void emptyAssignees_usesDefaultAssignee() {
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID defaultAssignee = UUID.randomUUID();
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId("tenant_1");
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config", Map.of()));
        when(ctx.defaultAssignee()).thenReturn(defaultAssignee);
        when(taskRepository.save(any(WorkflowTaskEntity.class))).thenAnswer(i -> i.getArguments()[0]);
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.NEEDS_APPROVAL, outcome);
        verify(taskRepository).save(argThat(task -> task.getAssignee().equals(defaultAssignee)));
    }

    @Test
    void invalidUuidInAssignees_skipsInvalid() {
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID validAssignee = UUID.randomUUID();
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId("tenant_1");
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config", Map.of("assignees", List.of("invalid-uuid", validAssignee.toString()))));
        when(taskRepository.save(any(WorkflowTaskEntity.class))).thenAnswer(i -> i.getArguments()[0]);
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.NEEDS_APPROVAL, outcome);
        verify(taskRepository, times(1)).save(any(WorkflowTaskEntity.class));
    }

    @Test
    void unresolvableAssignee_failsInstance_withoutGhostTask() {
        // A-7 负向用例：无 assignee / approverType / defaultAssignee 时，
        // 修复前会 nameUUIDFromBytes(tenantId) 造幽灵审批人 → 任务永久悬挂。
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId("tenant_1");
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config", Map.of()));
        when(ctx.defaultAssignee()).thenReturn(null);
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.FAILED, outcome);
        verify(taskRepository, never()).save(any(WorkflowTaskEntity.class));
        verify(instanceRepository).save(argThat(inst ->
                inst.getStatus() == WorkflowInstanceEntity.Status.FAILED
                        && inst.getErrorMessage() != null));
    }

    @Test
    void managerApprover_usesApplicantManagerId() {
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID applicant = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId("tenant_1");
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        instance.setTriggerDataJson("{\"data\":{\"applicantId\":\"" + applicant + "\"}}");

        com.nocobase.auth.UserEntity applicantUser = new com.nocobase.auth.UserEntity();
        applicantUser.setId(applicant);
        applicantUser.setDisplayName("申请人");
        applicantUser.setManagerId(manager);
        com.nocobase.auth.UserEntity managerUser = new com.nocobase.auth.UserEntity();
        managerUser.setId(manager);
        managerUser.setEnabled(true);
        when(userRepository.findById(applicant)).thenReturn(Optional.of(applicantUser));
        when(userRepository.findById(manager)).thenReturn(Optional.of(managerUser));

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config",
                Map.of("approverType", "MANAGER")));
        when(taskRepository.save(any(WorkflowTaskEntity.class))).thenAnswer(i -> i.getArguments()[0]);
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.NEEDS_APPROVAL, outcome);
        verify(taskRepository).save(argThat(task -> task.getAssignee().equals(manager)));
    }

    @Test
    void managerApprover_missingManager_failsWithoutTask() {
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID applicant = UUID.randomUUID();
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId("tenant_1");
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        instance.setTriggerDataJson("{\"data\":{\"applicantId\":\"" + applicant + "\"}}");

        com.nocobase.auth.UserEntity applicantUser = new com.nocobase.auth.UserEntity();
        applicantUser.setId(applicant);
        applicantUser.setDisplayName("无上级");
        when(userRepository.findById(applicant)).thenReturn(Optional.of(applicantUser));
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config",
                Map.of("approverType", "MANAGER")));

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.FAILED, outcome);
        verify(taskRepository, never()).save(any(WorkflowTaskEntity.class));
    }

    @Test
    void type_returnsApproval() {
        assertEquals("APPROVAL", handler.type());
    }
}
