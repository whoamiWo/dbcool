package com.nocobase.workflow;

import com.nocobase.notification.NotifyDispatcher;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * R2：工作流失败通知（以实例终态为准）。
 *
 * <p>修复前只有 {@code WorkflowTriggerListener} 的 catch 分支发失败通知，
 * 其余 5 个执行入口（Controller/Scheduler/TaskService/ApprovalService/PlaybookService）
 * 与 {@code NodeResult.FAILED}（如 A-7 审批人解析失败）全部静默。
 *
 * <p>本组件统一在 6 个入口执行结束后调用：实例 {@code status=FAILED} 即通知创建者；
 * {@code digest_key = workflow-failed:{instanceId}} 保证同一实例只留一条（聚合累加）。
 */
@Component
public class WorkflowFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFailureNotifier.class);

    private final WorkflowRepository workflowRepository;
    private final NotifyDispatcher notifyDispatcher;

    public WorkflowFailureNotifier(WorkflowRepository workflowRepository,
                                   NotifyDispatcher notifyDispatcher) {
        this.workflowRepository = workflowRepository;
        this.notifyDispatcher = notifyDispatcher;
    }

    /**
     * 若实例处于 FAILED 终态，给工作流创建者发一条失败通知。
     *
     * @param instance       执行后的实例（含最新 status）
     * @param workflowId     工作流 id
     * @param fallbackUserId 无 workflow 实体时的回退收件人（如剧本触发人）
     * @param workflowName   工作流名称（可空）
     * @param tenantId       租户
     */
    public void notifyIfFailed(WorkflowInstanceEntity instance, UUID workflowId,
                               UUID fallbackUserId, String workflowName, String tenantId) {
        if (instance == null
                || instance.getStatus() != WorkflowInstanceEntity.Status.FAILED) {
            return;
        }
        notifyFailure(instance, workflowId, fallbackUserId, workflowName, tenantId);
    }

    /**
     * 无条件发失败通知（供"异常抛出、实例尚未落 FAILED"的路径使用）。
     */
    public void notifyFailure(WorkflowInstanceEntity instance, UUID workflowId,
                              UUID fallbackUserId, String workflowName, String tenantId) {
        if (instance == null) return;
        try {
            WorkflowEntity workflow = workflowId == null ? null
                    : workflowRepository.findByIdAndTenantId(workflowId, tenantId).orElse(null);
            UUID recipient = workflow != null && workflow.getCreatedBy() != null
                    ? workflow.getCreatedBy() : fallbackUserId;
            if (recipient == null) {
                log.warn("[workflow] 实例 {} 失败但无可用收件人（无创建者/触发人）", instance.getId());
                return;
            }
            String name = workflowName != null && !workflowName.isBlank() ? workflowName
                    : (workflow != null && workflow.getName() != null ? workflow.getName() : "工作流");
            String reason = instance.getErrorMessage() != null && !instance.getErrorMessage().isBlank()
                    ? instance.getErrorMessage() : "执行失败，请到实例详情查看";
            notifyDispatcher.notifyOne(
                    NotifyDispatcher.WORKFLOW_FAILED,
                    recipient,
                    new NotifyDispatcher.Payload(
                            tenantId,
                            "工作流执行失败",
                            "工作流「" + name + "」执行失败：" + reason,
                            instance.getId().toString(),
                            "/designer/instances/" + instance.getId(),
                            "HIGH",
                            "workflow-failed:" + instance.getId()));
        } catch (Exception e) {
            // 通知失败不得影响主流程；但必须可观测
            log.warn("[workflow] 失败通知发送异常 instance={}: {}",
                    instance.getId(), e.getMessage());
        }
    }
}
