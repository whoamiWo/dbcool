package com.nocobase.workflow.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.RoleEntity;
import com.nocobase.auth.RoleRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.auth.UserRoleEntity;
import com.nocobase.auth.UserRoleRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审批节点 handler。
 *
 * <p><b>A-7 修复（2026-11-04）</b>：修复前无 {@code assignee} 配置时用
 * {@code UUID.nameUUIDFromBytes(tenantId)} 合成一个<b>不存在的用户</b>当审批人 ——
 * 任务挂在幽灵用户上，没人能审、流程永久悬挂，UI 却显示"待审批"。
 *
 * <p>现在的审批人解析规则（{@code config}）：
 * <ol>
 *   <li>{@code assignees: [uuid...]} —— 多人各一条任务、全部通过才推进（弱会签，保留既有能力）</li>
 *   <li>{@code approverType=USER} + {@code assignee} —— 指定人</li>
 *   <li>{@code approverType=MANAGER} —— 发起人（trigger_data.applicantId）的
 *       {@code users.manager_id}，即"上级审批"</li>
 *   <li>{@code approverType=ROLE} + {@code roleId} / {@code roleName} —— 角色成员展开</li>
 * </ol>
 *
 * <p><b>无法解析时绝不创建任务</b>：把实例置为 FAILED + 明确错误信息并返回
 * {@link NodeOutcome#FAILED}，而不是造一个假审批人。提交前的
 * {@code manager_id} 缺失拦截在 {@code ApprovalService} 侧（更早、对用户更友好）。
 */
@Component
public class ApprovalNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNodeHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;

    public ApprovalNodeHandler(
            WorkflowInstanceRepository instanceRepository,
            WorkflowTaskRepository taskRepository,
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleRepository roleRepository
    ) {
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    public String type() {
        return "APPROVAL";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        WorkflowInstanceEntity instance = ctx.instance();
        String nodeId = (String) ctx.node().get("id");

        List<UUID> assignees;
        try {
            assignees = resolveAssignees(ctx);
        } catch (AssigneeResolutionException e) {
            // A-7：宁可实例失败，也不创建指向不存在用户的任务
            log.error("[workflow] 审批节点 {} 无法解析审批人，实例 {} 标记 FAILED: {}",
                    nodeId, instance.getId(), e.getMessage());
            instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
            instance.setErrorMessage("审批节点 " + nodeId + " 无法解析审批人：" + e.getMessage());
            instance.setFinishedAt(Instant.now());
            instanceRepository.save(instance);
            return NodeOutcome.FAILED;
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

    // ============================================================
    //  审批人解析
    // ============================================================

    private List<UUID> resolveAssignees(NodeExecutionContext ctx) {
        Map<String, Object> config = configOf(ctx);

        // 1) 显式多人列表（弱会签）
        List<UUID> explicit = parseUuidCollection(config.get("assignees"));
        if (!explicit.isEmpty()) return dedupe(explicit);

        // 2) 显式单人
        List<UUID> single = parseUuidCollection(config.get("assignee"));
        if (!single.isEmpty()) return single;

        String approverType = str(config.get("approverType"));
        if (approverType == null || approverType.isBlank()) {
            // 兼容旧配置：没写 approverType 但有 roleId/roleName → 视为 ROLE
            approverType = (config.get("roleId") != null || config.get("roleName") != null)
                    ? "ROLE" : "USER";
        }

        switch (approverType.toUpperCase()) {
            case "USER" -> {
                List<UUID> ids = parseUuidCollection(
                        config.get("assigneeId") != null ? config.get("assigneeId") : config.get("userId"));
                if (!ids.isEmpty()) return ids;
                // 引擎传入的 defaultAssignee 是工作流创建人（真实用户），可作最后兜底；
                // 绝不再合成 nameUUIDFromBytes(tenantId) 这种不存在的用户（A-7）。
                if (ctx.defaultAssignee() != null) {
                    return List.of(ctx.defaultAssignee());
                }
                throw new AssigneeResolutionException("approverType=USER 但未配置有效 assignee");
            }
            case "MANAGER" -> {
                return List.of(resolveManager(ctx));
            }
            case "ROLE" -> {
                return resolveRoleMembers(config, ctx.instance().getTenantId());
            }
            default -> throw new AssigneeResolutionException("未知 approverType: " + approverType);
        }
    }

    /** 上级审批：发起人 trigger_data.applicantId → users.manager_id。 */
    private UUID resolveManager(NodeExecutionContext ctx) {
        String applicantRaw = applicantIdOf(ctx.instance());
        if (applicantRaw == null) {
            throw new AssigneeResolutionException(
                    "approverType=MANAGER 但触发数据缺少 applicantId，无法定位发起人");
        }
        UUID applicantId;
        try {
            applicantId = UUID.fromString(applicantRaw);
        } catch (IllegalArgumentException e) {
            throw new AssigneeResolutionException("applicantId 不是合法 UUID: " + applicantRaw);
        }
        UserEntity applicant = userRepository.findById(applicantId)
                .orElseThrow(() -> new AssigneeResolutionException(
                        "发起人不存在: " + applicantId));
        UUID managerId = applicant.getManagerId();
        if (managerId == null) {
            throw new AssigneeResolutionException(
                    "发起人 " + applicant.getDisplayName() + " 未配置直属上级（users.manager_id）");
        }
        // 上级账号必须存在且可用，否则同样是幽灵审批人
        UserEntity manager = userRepository.findById(managerId)
                .orElseThrow(() -> new AssigneeResolutionException(
                        "发起人的上级账号不存在: " + managerId));
        if (!manager.isActiveEmployee()) {
            throw new AssigneeResolutionException(
                    "发起人的上级账号已禁用/离职: " + managerId);
        }
        return managerId;
    }

    private List<UUID> resolveRoleMembers(Map<String, Object> config, String tenantId) {
        RoleEntity role = null;
        Object roleIdRaw = config.get("roleId");
        List<UUID> roleIds = parseUuidCollection(roleIdRaw);
        if (!roleIds.isEmpty()) {
            role = roleRepository.findByIdAndTenantId(roleIds.get(0), tenantId).orElse(null);
        }
        if (role == null) {
            String roleName = str(config.get("roleName"));
            if (roleName != null && !roleName.isBlank()) {
                role = roleRepository.findByNameAndTenantId(roleName, tenantId).orElse(null);
            }
        }
        if (role == null) {
            throw new AssigneeResolutionException("approverType=ROLE 但角色不存在（roleId/roleName 均未命中）");
        }
        List<UUID> members = new ArrayList<>();
        for (UserRoleEntity ur : userRoleRepository.findByIdRoleId(role.getId())) {
            UserEntity u = userRepository.findById(ur.getId().getUserId()).orElse(null);
            if (u != null && u.isActiveEmployee()) members.add(u.getId());
        }
        if (members.isEmpty()) {
            throw new AssigneeResolutionException(
                    "角色 " + role.getName() + " 下没有可用成员，无法创建审批任务");
        }
        return dedupe(members);
    }

    /** 从 trigger_data JSON 读取发起人 id（兼容多种键名）。 */
    private String applicantIdOf(WorkflowInstanceEntity instance) {
        String raw = instance.getTriggerDataJson();
        if (raw == null || raw.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(raw);
            JsonNode data = root.path("data");
            for (String key : List.of("applicantId", "applicant_id", "createdBy", "created_by")) {
                String v = root.path(key).asText("");
                if (!v.isBlank()) return v;
                v = data.path(key).asText("");
                if (!v.isBlank()) return v;
            }
        } catch (Exception e) {
            log.warn("[workflow] trigger_data 解析失败 instance={}: {}",
                    instance.getId(), e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(NodeExecutionContext ctx) {
        Object raw = ctx.node().get("config");
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<UUID> parseUuidCollection(Object raw) {
        List<UUID> ids = new ArrayList<>();
        if (raw instanceof Collection<?> col) {
            for (Object o : col) {
                UUID id = toUuid(o);
                if (id != null) ids.add(id);
            }
        } else {
            UUID id = toUuid(raw);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static UUID toUuid(Object o) {
        if (o instanceof UUID u) return u;
        if (o instanceof String s && !s.isBlank()) {
            try {
                return UUID.fromString(s.trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<UUID> dedupe(List<UUID> ids) {
        Set<UUID> set = new LinkedHashSet<>(ids);
        return new ArrayList<>(set);
    }

    /** 审批人无法解析（业务错误，不是系统故障）。 */
    static class AssigneeResolutionException extends RuntimeException {
        AssigneeResolutionException(String message) {
            super(message);
        }
    }
}
