package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.audit.AuditService;
import com.nocobase.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流任务审批服务（Wave4 待办池 #10 收尾）。
 *
 * <p>把此前内联在 {@code WorkflowController#approve/reject} 的任务审批与推进逻辑
 * 逐行搬迁至此，供两个入口共用：
 * <ul>
 *   <li>平台内：工作流任务页面手动审批；</li>
 *   <li>外部：钉钉审批回调（{@code DingTalkApprovalService#handleApprovalCallback}）。</li>
 * </ul>
 *
 * <p>逻辑保持与搬迁前一致（含会签等待、数组模式下游推进、APPROVAL 下一节点创建），
 * 不做行为变更；唯一新增是 rejectTask 的审计归属显式传入（webhook 无 TenantContext）。
 */
@Service
public class WorkflowTaskService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowTaskService.class);

    private final WorkflowTaskRepository taskRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine engine;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public WorkflowTaskService(WorkflowTaskRepository taskRepository,
                               WorkflowInstanceRepository instanceRepository,
                               WorkflowRepository workflowRepository,
                               WorkflowEngine engine,
                               AuditService auditService,
                               ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.instanceRepository = instanceRepository;
        this.workflowRepository = workflowRepository;
        this.engine = engine;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 审批结果：instance=更新后的实例；waitingForOthers=会签剩余；pendingCount=剩余未审批数。 */
    public record ApproveOutcome(WorkflowInstanceEntity instance, boolean waitingForOthers, int pendingCount) {}

    /**
     * 审批通过任务并推进工作流。
     *
     * @return 更新后的工作流实例（PENDING=等待会签/下一审批节点，COMPLETED=全部完成）
     * @throws ResponseStatusException 404（任务不存在）/ 400（任务已处理）
     */
    @Transactional
    public ApproveOutcome approveTask(UUID taskId, String comment) {
        WorkflowTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task 不存在"));
        if (task.getStatus() != WorkflowTaskEntity.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task 已处理");
        }

        task.setStatus(WorkflowTaskEntity.Status.APPROVED);
        task.setComment(comment);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);

        // Week 41 复核 D4b.5 会签:同一审批节点若还有其他人未审批,则不推进工作流。
        // 单人审批时该列表为空,行为与改造前完全一致(向后兼容)。
        if (task.getNodeId() != null) {
            List<WorkflowTaskEntity> pendingSiblings = taskRepository.findByInstanceIdAndNodeIdAndStatus(
                    task.getInstanceId(), task.getNodeId(), WorkflowTaskEntity.Status.PENDING);
            if (!pendingSiblings.isEmpty()) {
                WorkflowInstanceEntity waiting =
                        instanceRepository.findById(task.getInstanceId()).orElseThrow();
                return new ApproveOutcome(waiting, true, pendingSiblings.size());
            }
        }

        // 推进实例到下一个节点 — 重新触发 trigger 逻辑(简化)
        WorkflowInstanceEntity instance = instanceRepository.findById(task.getInstanceId()).orElseThrow();
        WorkflowEntity w = workflowRepository.findById(instance.getWorkflowId()).orElseThrow();

        // 标记实例 RUNNING
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        instanceRepository.save(instance);

        // 继续执行后续节点
        List<Map<String, Object>> nodes;
        try {
            nodes = objectMapper.readValue(w.getNodesJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            nodes = List.of();
        }

        int nextIdx = instance.getCurrentNodeIndex() + 1;
        for (int i = nextIdx; i < nodes.size(); i++) {
            Map<String, Object> node = nodes.get(i);
            instance.setCurrentNodeIndex(i);

            String nodeType = (String) node.get("type");
            if ("APPROVAL".equals(nodeType)) {
                // 下一个审批节点:创建 task
                WorkflowTaskEntity nextTask = new WorkflowTaskEntity();
                nextTask.setId(UUID.randomUUID());
                nextTask.setInstanceId(instance.getId());
                nextTask.setTenantId(instance.getTenantId());
                nextTask.setNodeId((String) node.get("id"));
                nextTask.setNodeType("APPROVAL");
                nextTask.setAssignee(w.getCreatedBy());
                nextTask.setStatus(WorkflowTaskEntity.Status.PENDING);
                nextTask.setCreatedAt(Instant.now());
                taskRepository.save(nextTask);

                instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
                instanceRepository.save(instance);
                return new ApproveOutcome(instance, false, 0);
            } else if ("NOTIFICATION".equals(nodeType)) {
                log.info("[workflow {} node {}] notification: {}", w.getId(), i, node.get("config"));
            }
        }

        // 全部跑完
        instance.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
        instance.setFinishedAt(Instant.now());
        instanceRepository.save(instance);

        return new ApproveOutcome(instance, false, 0);
    }

    /**
     * 驳回任务并把实例置为 FAILED。
     *
     * @param auditActor 审计操作者（webhook 场景可为 null，此时用任务 assignee）
     */
    @Transactional
    public WorkflowInstanceEntity rejectTask(UUID taskId, String comment, UUID auditActor) {
        WorkflowTaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task 不存在"));
        if (task.getStatus() != WorkflowTaskEntity.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task 已处理");
        }
        task.setStatus(WorkflowTaskEntity.Status.REJECTED);
        task.setComment(comment);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);

        WorkflowInstanceEntity instance = instanceRepository.findById(task.getInstanceId()).orElseThrow();
        instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
        instance.setErrorMessage("用户在节点 " + task.getNodeId() + " 拒绝");
        instance.setFinishedAt(Instant.now());
        instanceRepository.save(instance);

        WorkflowEntity wf = workflowRepository.findById(instance.getWorkflowId()).orElse(null);
        String auditTenant = (TenantContext.currentTenantId() != null)
                ? TenantContext.currentTenantId() : instance.getTenantId();
        UUID actor = (auditActor != null) ? auditActor : task.getAssignee();
        auditService.log(auditTenant, actor, null,
                "REJECT", "workflow_task", task.getId().toString(),
                Map.of("workflow", wf != null ? wf.getId().toString() : "", "comment", task.getComment() == null ? "" : task.getComment()));

        return instance;
    }

    /**
     * Wave4 待办池 #10（回调专用）：按钉钉审批实例 ID 反查并推进绑定的工作流实例。
     *
     * <p>寻找该实例上全部 PENDING 任务：
     * <ul>
     *   <li>agree → {@link #approveTask}（推进下游节点，与平台内审批同一逻辑）；</li>
     *   <li>reject → {@link #rejectTask}（实例 FAILED）。</li>
     * </ul>
     * 会签场景 approveTask 会因剩余 PENDING 任务返回 PENDING 实例 —— 属正常中间态。
     *
     * @return 是否处理了至少一个实例
     */
    /** Wave4 待办池 #10：钉钉审批回调事件消费（解耦，避免构造循环依赖）。 */
    @org.springframework.context.event.EventListener
    @Transactional
    public void onDingTalkApprovalCallback(DingTalkApprovalCallbackEvent event) {
        boolean handled = approveByExternalBinding(event.dingtalkInstanceId(), event.agree(), event.comment());
        if (!handled) {
            log.warn("[workflow-task] 钉钉审批回调事件无绑定命中 instance={}", event.dingtalkInstanceId());
        }
    }

    public boolean approveByExternalBinding(String bindingKey, boolean agree, String comment) {
        List<WorkflowInstanceEntity> instances =
                instanceRepository.findByTriggerDataJsonContaining(bindingKey);
        if (instances.isEmpty()) {
            log.warn("[workflow-task] 外部审批绑定未命中 key={} agree={}", bindingKey, agree);
            return false;
        }
        boolean handled = false;
        for (WorkflowInstanceEntity instance : instances) {
            List<WorkflowTaskEntity> tasks = taskRepository.findByInstanceId(instance.getId());
            WorkflowTaskEntity pending = tasks.stream()
                    .filter(t -> t.getStatus() == WorkflowTaskEntity.Status.PENDING)
                    .findFirst().orElse(null);
            if (pending == null) {
                log.warn("[workflow-task] 实例 {} 无 PENDING 任务，跳过", instance.getId());
                continue;
            }
            if (agree) {
                approveTask(pending.getId(), comment);
            } else {
                rejectTask(pending.getId(), comment, null);
            }
            handled = true;
            log.info("[workflow-task] 外部审批已推进实例={} agree={}", instance.getId(), agree);
        }
        return handled;
    }
}
