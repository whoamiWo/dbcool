package com.nocobase.approval;

import com.nocobase.auth.RoleRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * R1e：审批单据可见性 —— 审批详情与搜索结果**共用同一个谓词**。
 *
 * <p>可见范围：申请人 ∪ 抄送人 ∪ 该实例任务处理人（历史+当前）∪ ADMIN。
 * 修复前 {@code ApprovalService.detail} 只按租户过滤，任何登录用户拿到单据 id
 * 就能读到别人的请假理由/报销金额/审批轨迹。
 */
@Service
public class ApprovalVisibilityService {

    private final ApprovalCcRepository ccRepository;
    private final WorkflowTaskRepository taskRepository;
    private final RoleRepository roleRepository;

    public ApprovalVisibilityService(ApprovalCcRepository ccRepository,
                                     WorkflowTaskRepository taskRepository,
                                     RoleRepository roleRepository) {
        this.ccRepository = ccRepository;
        this.taskRepository = taskRepository;
        this.roleRepository = roleRepository;
    }

    /** 判定用户是否可见某审批单据。userId 为 null 时一律不可见（fail-closed）。 */
    public boolean canView(String tenantId, ApprovalRequestEntity request, UUID userId) {
        if (request == null || userId == null) return false;
        if (request.getApplicantId() != null && request.getApplicantId().equals(userId)) {
            return true;
        }
        boolean cc = ccRepository.findByRequestId(request.getId()).stream()
                .anyMatch(c -> userId.equals(c.getUserId()));
        if (cc) return true;
        if (request.getInstanceId() != null
                && taskRepository.existsByInstanceIdAndAssignee(request.getInstanceId(), userId)) {
            return true;
        }
        // ADMIN：与其它管理端点一致的租户内管理员
        return roleRepository.findRoleNamesByUserId(userId, tenantId).stream()
                .anyMatch("admin"::equalsIgnoreCase);
    }

    /** 详情读取统一入口：不可见 → 404（不泄露单据是否存在）。 */
    public void assertCanView(String tenantId, ApprovalRequestEntity request, UUID userId) {
        if (!canView(tenantId, request, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批单据不存在");
        }
    }
}
