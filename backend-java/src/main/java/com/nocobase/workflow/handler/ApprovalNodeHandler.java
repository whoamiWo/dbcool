package com.nocobase.workflow.handler;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowNodeHandler;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ApprovalNodeHandler implements WorkflowNodeHandler {

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTaskRepository taskRepository;

    public ApprovalNodeHandler(
            WorkflowInstanceRepository instanceRepository,
            WorkflowTaskRepository taskRepository
    ) {
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    public String type() {
        return "APPROVAL";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        WorkflowInstanceEntity instance = ctx.instance();
        String nodeId = (String) ctx.node().get("id");

        UUID assignee = resolveAssignee(ctx);

        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setId(UUID.randomUUID());
        task.setInstanceId(instance.getId());
        task.setNodeId(nodeId);
        task.setAssignee(assignee);
        task.setStatus(WorkflowTaskEntity.Status.PENDING);
        taskRepository.save(task);

        instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
        instanceRepository.save(instance);

        return NodeOutcome.NEEDS_APPROVAL;
    }

    private UUID resolveAssignee(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());
        Object a = config.get("assignee");
        if (a instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (Exception ignored) {}
        }
        if (ctx.defaultAssignee() != null) return ctx.defaultAssignee();
        return UUID.nameUUIDFromBytes(ctx.instance().getTenantId().getBytes());
    }
}
