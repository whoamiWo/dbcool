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
    private final ApprovalNodeHandler handler = new ApprovalNodeHandler(instanceRepository, taskRepository);

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
    void nullTenantId_usesGeneratedUuidFromDefaultTenant() {
        UUID instanceId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(instanceId);
        instance.setTenantId(null);
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        when(ctx.instance()).thenReturn(instance);
        when(ctx.node()).thenReturn(Map.of("id", nodeId.toString(), "config", Map.of()));
        when(ctx.defaultAssignee()).thenReturn(null);
        when(taskRepository.save(any(WorkflowTaskEntity.class))).thenAnswer(i -> i.getArguments()[0]);
        when(instanceRepository.save(any(WorkflowInstanceEntity.class))).thenAnswer(i -> i.getArguments()[0]);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.NEEDS_APPROVAL, outcome);
        verify(taskRepository).save(any(WorkflowTaskEntity.class));
    }

    @Test
    void type_returnsApproval() {
        assertEquals("APPROVAL", handler.type());
    }
}
