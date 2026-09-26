package com.nocobase.workflow.handler;

import com.nocobase.tenant.TenantContext;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowNodeHandler;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 审批节点 handler。
 *
 * <p>Week 41 复核修复:初版漏设 {@code nodeType} 与 {@code createdAt},而这两列在
 * {@code WorkflowTaskEntity} 上均为 {@code nullable = false} —— 一旦启用策略分发
 * 就会触发 not-null constraint violation。现按 legacy
 * {@code WorkflowEngine.createApprovalTask()}(:250-259)补齐。
 *
 * <p>Week 41 复核 D4b.5 会签:支持 {@code config.assignees} 多人审批 ——
 * 为每个审批人各创建一条 PENDING 任务,全部审批通过后才推进工作流
 * (推进判断见 {@code WorkflowController#approve})。
 */
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

        List<UUID> assignees = resolveAssignees(ctx);
        if (assignees.isEmpty()) {
            assignees = List.of(resolveAssignee(ctx));
        }

        for (UUID assignee : assignees) {
            WorkflowTaskEntity task = new WorkflowTaskEntity();
            task.setId(UUID.randomUUID());
            task.setInstanceId(instance.getId());
            task.setTenantId(instance.getTenantId());
            task.setNodeId(nodeId);
            // nodeType / createdAt 均为 NOT NULL,漏设会在 save 时触发约束冲突
            task.setNodeType("APPROVAL");
            task.setAssignee(assignee);
            task.setStatus(WorkflowTaskEntity.Status.PENDING);
            task.setCreatedAt(Instant.now());
            taskRepository.save(task);
        }

        instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
        instanceRepository.save(instance);

        return NodeOutcome.NEEDS_APPROVAL;
    }

    /**
     * 解析多人审批列表({@code config.assignees}),为空表示走单人逻辑。
     *
     * <p>元素可为 UUID 字符串或 UUID 对象,非法值静默跳过。
     */
    @SuppressWarnings("unchecked")
    private List<UUID> resolveAssignees(NodeExecutionContext ctx) {
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());
        Object raw = config.get("assignees");
        if (!(raw instanceof Collection<?> col) || col.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (Object o : col) {
            if (o instanceof UUID u) {
                ids.add(u);
            } else if (o instanceof String s && !s.isBlank()) {
                try { ids.add(UUID.fromString(s)); } catch (Exception ignored) {}
            }
        }
        return ids;
    }

    private UUID resolveAssignee(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());
        Object a = config.get("assignee");
        if (a instanceof String s && !s.isBlank()) {
            try { return UUID.fromString(s); } catch (Exception ignored) {}
        }
        if (ctx.defaultAssignee() != null) return ctx.defaultAssignee();
        String tenantId = ctx.instance().getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            // 防 NPE:初版直接 getBytes() 会在 tenantId 为 null 时抛异常
            tenantId = TenantContext.DEFAULT_TENANT;
        }
        return UUID.nameUUIDFromBytes(tenantId.getBytes());
    }
}
