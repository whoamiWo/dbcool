package com.nocobase.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserRepository;
import com.nocobase.workflow.WorkflowEngine;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 审批单据服务（E2）。
 *
 * <p><b>决策 D9</b>：审批链由 {@link WorkflowEngine} 驱动（单引擎）。
 * 提交 = 创建 {@link WorkflowInstanceEntity} 并执行；实例状态是审批状态的
 * <b>唯一事实</b>（{@link #syncStatus} 负责投影到 {@code approval_request.status}）。
 *
 * <p>A-7 配套：<b>提交前</b>就校验审批人可解析（尤其 {@code MANAGER} 需要
 * {@code users.manager_id}），避免进入工作流后才产生 FAILED 实例或幽灵任务。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApprovalTemplateRepository templateRepository;
    private final ApprovalRequestRepository requestRepository;
    private final ApprovalCcRepository ccRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private final WorkflowEngine engine;
    private final UserRepository userRepository;
    /** D4：钉钉推送开关默认关，本地 approval_request 为 SSOT。 */
    private final com.nocobase.integration.dingtalk.DingTalkApprovalService dingTalkApprovalService;
    private final com.nocobase.notification.NotifyDispatcher notifyDispatcher;
    private final com.nocobase.search.UnifiedSearchService searchService;
    private final boolean dingTalkPushEnabled;

    public ApprovalService(ApprovalTemplateRepository templateRepository,
                           ApprovalRequestRepository requestRepository,
                           ApprovalCcRepository ccRepository,
                           WorkflowRepository workflowRepository,
                           WorkflowInstanceRepository instanceRepository,
                           WorkflowTaskRepository taskRepository,
                           WorkflowEngine engine,
                           UserRepository userRepository,
                           com.nocobase.integration.dingtalk.DingTalkApprovalService dingTalkApprovalService,
                           com.nocobase.notification.NotifyDispatcher notifyDispatcher,
                           com.nocobase.search.UnifiedSearchService searchService,
                           @org.springframework.beans.factory.annotation.Value(
                                   "${approval.dingtalk.push.enabled:false}") boolean dingTalkPushEnabled) {
        this.templateRepository = templateRepository;
        this.requestRepository = requestRepository;
        this.ccRepository = ccRepository;
        this.workflowRepository = workflowRepository;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.engine = engine;
        this.userRepository = userRepository;
        this.dingTalkApprovalService = dingTalkApprovalService;
        this.notifyDispatcher = notifyDispatcher;
        this.searchService = searchService;
        this.dingTalkPushEnabled = dingTalkPushEnabled;
    }

    // ============================================================
    //  模板
    // ============================================================

    public List<ApprovalTemplateEntity> templates(String tenantId) {
        return templateRepository.findByTenantIdAndEnabledTrueOrderByCreatedAtDesc(tenantId);
    }

    /**
     * 由预设生成「发起 → 审批（可多级）→ 结束」三段式流程，并创建模板。
     *
     * @param levels   审批级数（1..5）
     * @param approverType USER / MANAGER / ROLE
     * @param assignee  USER 时的审批人；ROLE 时为角色 id
     */
    @Transactional
    public ApprovalTemplateEntity createFromPreset(String tenantId, UUID creatorId, String name,
                                                    String category, int levels,
                                                    String approverType, String assignee) {
        int safeLevels = Math.max(1, Math.min(levels, 5));
        String type = approverType == null || approverType.isBlank()
                ? ApprovalTemplateEntity.APPROVER_USER : approverType.toUpperCase();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (int i = 1; i <= safeLevels; i++) {
            String nodeId = "approve-" + i;
            Map<String, Object> config = new HashMap<>();
            config.put("approverType", type);
            if (ApprovalTemplateEntity.APPROVER_USER.equals(type)) {
                if (assignee == null || assignee.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "指定人审批必须提供 assignee");
                }
                config.put("assignee", assignee);
            } else if (ApprovalTemplateEntity.APPROVER_ROLE.equals(type)) {
                if (assignee == null || assignee.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "角色审批必须提供 roleId");
                }
                config.put("roleId", assignee);
            }
            Map<String, Object> node = new HashMap<>();
            node.put("id", nodeId);
            node.put("type", "APPROVAL");
            node.put("name", "第 " + i + " 级审批");
            node.put("config", config);
            nodes.add(node);
            if (i > 1) {
                Map<String, Object> edge = new HashMap<>();
                edge.put("source", "approve-" + (i - 1));
                edge.put("target", nodeId);
                edges.add(edge);
            }
        }

        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName("approval_" + w.getId().toString().substring(0, 8));
        w.setTitle(name);
        w.setDescription("由审批模板向导生成（" + safeLevels + " 级审批）");
        w.setCollectionName("approval_request");
        w.setTenantId(tenantId);
        w.setEnabled(true);
        w.setCreatedAt(Instant.now());
        w.setCreatedBy(creatorId);
        try {
            w.setTriggerJson(MAPPER.writeValueAsString(Map.of("type", "FORM_SUBMIT")));
            w.setNodesJson(MAPPER.writeValueAsString(nodes));
            w.setEdgesJson(MAPPER.writeValueAsString(edges));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成工作流失败");
        }
        workflowRepository.save(w);

        ApprovalTemplateEntity t = new ApprovalTemplateEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(tenantId);
        t.setName(name);
        t.setCategory(category);
        t.setWorkflowId(w.getId());
        t.setApproverType(type);
        t.setEnabled(true);
        t.setCreatedAt(Instant.now());
        return templateRepository.save(t);
    }

    // ============================================================
    //  提交
    // ============================================================

    @Transactional
    public Map<String, Object> submit(String tenantId, UUID applicantId,
                                      UUID templateId, Map<String, Object> formData,
                                      List<UUID> ccUserIds) {
        ApprovalTemplateEntity template = templateRepository.findByIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "审批模板不存在"));
        if (!template.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批模板已停用");
        }
        WorkflowEntity workflow = workflowRepository
                .findByIdAndTenantId(template.getWorkflowId(), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "审批模板未绑定有效工作流（D9：workflow_id 必填）"));

        UserEntity applicant = userRepository.findByIdAndTenantId(applicantId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "申请人不存在"));

        // A-7 配套：提交前校验审批人可解析，manager 缺失直接拦住
        validateApprovers(workflow, applicant);

        ApprovalRequestEntity request = new ApprovalRequestEntity();
        request.setId(UUID.randomUUID());
        request.setTenantId(tenantId);
        request.setTemplateId(templateId);
        request.setApplicantId(applicantId);
        request.setFormDataJson(toJson(formData == null ? Map.of() : formData));
        request.setStatus(ApprovalRequestEntity.STATUS_PENDING);
        request.setSubmittedAt(Instant.now());
        requestRepository.save(request);

        // 抄送人（提交时固化，审批结束时通知）
        if (ccUserIds != null) {
            final UUID requestIdForCc = request.getId();
            new LinkedHashSet<>(ccUserIds).forEach(uid ->
                    ccRepository.save(new ApprovalCcEntity(requestIdForCc, uid)));
        }

        // 触发工作流实例（D9）
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setWorkflowId(workflow.getId());
        instance.setTenantId(tenantId);
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        instance.setCurrentNodeIndex(0);
        instance.setStartedAt(Instant.now());
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("applicantId", applicantId.toString());
        trigger.put("requestId", request.getId().toString());
        trigger.put("formData", formData == null ? Map.of() : formData);
        trigger.put("data", Map.of("applicantId", applicantId.toString()));
        instance.setTriggerDataJson(toJson(trigger));
        instance = instanceRepository.save(instance);
        request.setInstanceId(instance.getId());
        requestRepository.save(request);

        List<Map<String, Object>> nodes = parseList(workflow.getNodesJson());
        List<Map<String, Object>> edges = parseList(workflow.getEdgesJson());
        WorkflowEngine.NodeResult result;
        if (!edges.isEmpty() && !nodes.isEmpty()) {
            result = engine.executeGraphFrom(instance, nodes, edges,
                    String.valueOf(nodes.get(0).get("id")), workflow.getCreatedBy());
        } else {
            result = engine.executeFrom(instance, nodes, 0, workflow.getCreatedBy());
        }
        // D4：钉钉审批推送（开关默认关；失败只记日志，不阻断本地流程）
        if (dingTalkPushEnabled && template.getExternalProcessCode() != null
                && !template.getExternalProcessCode().isBlank()) {
            try {
                String externalId = dingTalkApprovalService.createApproval(
                        template.getExternalProcessCode(),
                        template.getName() + " - " + applicant.getDisplayName(),
                        formData == null ? Map.of() : formData);
                if (externalId != null && !externalId.isBlank()) {
                    request.setExternalInstanceId(externalId);
                    requestRepository.save(request);
                    // 回调按 trigger_data 中的 instance id 反查（见 approveByExternalBinding）
                    trigger.put("dingtalkInstanceId", externalId);
                    instance.setTriggerDataJson(toJson(trigger));
                    instanceRepository.save(instance);
                }
            } catch (Exception e) {
                log.warn("[approval] 钉钉推送失败（本地流程继续）request={}: {}",
                        request.getId(), e.getMessage());
            }
        }
        request = syncStatus(request, instance);
        // E3：第一级审批待办通知（后续级别的待办由 WorkflowTaskService 在推进后发出）
        notifyPendingTasks(instance, request.getId(), template.getName());
        indexApproval(request);
        log.info("[approval] 提交 request={} template={} applicant={} result={} status={}",
                request.getId(), templateId, applicantId, result, request.getStatus());
        return detailOf(request);
    }

    /** 提交前校验：每个审批节点都必须能解析出真实审批人。 */
    private void validateApprovers(WorkflowEntity workflow, UserEntity applicant) {
        List<Map<String, Object>> nodes = parseList(workflow.getNodesJson());
        if (nodes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批工作流没有节点");
        }
        boolean hasApprovalNode = false;
        for (Map<String, Object> node : nodes) {
            if (!"APPROVAL".equalsIgnoreCase(String.valueOf(node.get("type")))) continue;
            hasApprovalNode = true;
            Map<String, Object> config = asMap(node.get("config"));
            Object assignees = config.get("assignees");
            if (assignees instanceof Collection<?> c && !c.isEmpty()) continue;
            if (config.get("assignee") != null) continue;
            String type = config.get("approverType") == null
                    ? null : config.get("approverType").toString().toUpperCase();
            if (ApprovalTemplateEntity.APPROVER_MANAGER.equals(type)) {
                if (applicant.getManagerId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "该审批需要直属上级，但你的账号未配置上级（users.manager_id），请联系管理员");
                }
                continue;
            }
            if (ApprovalTemplateEntity.APPROVER_ROLE.equals(type)) {
                if (config.get("roleId") == null && config.get("roleName") == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "审批节点未配置审批角色（roleId/roleName）");
                }
                continue;
            }
            if (ApprovalTemplateEntity.APPROVER_USER.equals(type) || type == null) {
                if (config.get("assigneeId") == null && config.get("userId") == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "审批节点未配置审批人（审批模板配置不完整）");
                }
            }
        }
        if (!hasApprovalNode) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "审批工作流不包含审批节点");
        }
    }

    // ============================================================
    //  查询
    // ============================================================

    public List<Map<String, Object>> listMine(String tenantId, UUID applicantId) {
        return requestRepository.findByTenantIdAndApplicantIdOrderBySubmittedAtDesc(tenantId, applicantId)
                .stream().map(r -> summaryOf(syncStatus(r, null))).toList();
    }

    public List<Map<String, Object>> listTodo(String tenantId, UUID userId) {
        List<WorkflowTaskEntity> tasks = taskRepository
                .findByTenantIdAndAssigneeAndStatus(tenantId, userId, WorkflowTaskEntity.Status.PENDING);
        if (tasks.isEmpty()) return List.of();
        Map<UUID, WorkflowTaskEntity> byInstance = new HashMap<>();
        for (WorkflowTaskEntity t : tasks) {
            byInstance.putIfAbsent(t.getInstanceId(), t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApprovalRequestEntity r : requestRepository.findByTenantIdAndInstanceIdIn(
                tenantId, byInstance.keySet())) {
            ApprovalRequestEntity synced = syncStatus(r, null);
            Map<String, Object> m = summaryOf(synced);
            WorkflowTaskEntity task = byInstance.get(synced.getInstanceId());
            if (task != null) m.put("taskId", task.getId().toString());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> listCc(String tenantId, UUID userId) {
        Set<UUID> requestIds = new LinkedHashSet<>();
        ccRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .forEach(cc -> requestIds.add(cc.getRequestId()));
        if (requestIds.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID rid : requestIds) {
            requestRepository.findByIdAndTenantId(rid, tenantId)
                    .ifPresent(r -> out.add(summaryOf(syncStatus(r, null))));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> detail(String tenantId, UUID requestId) {
        ApprovalRequestEntity r = requestRepository.findByIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "审批单据不存在"));
        return detailOf(syncStatus(r, null));
    }

    @Transactional
    public Map<String, Object> cancel(String tenantId, UUID requestId, UUID userId) {
        ApprovalRequestEntity r = requestRepository.findByIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "审批单据不存在"));
        if (!r.getApplicantId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有发起人可以撤回");
        }
        r = syncStatus(r, null);
        if (!ApprovalRequestEntity.STATUS_PENDING.equals(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有审批中的单据可以撤回");
        }
        if (r.getInstanceId() != null) {
            WorkflowInstanceEntity instance = instanceRepository.findById(r.getInstanceId()).orElse(null);
            if (instance != null) {
                instance.setStatus(WorkflowInstanceEntity.Status.TERMINATED);
                instance.setFinishedAt(Instant.now());
                instanceRepository.save(instance);
            }
            // 关闭未完成任务（已审批的不回滚）
            for (WorkflowTaskEntity t : taskRepository.findByInstanceIdAndStatus(
                    r.getInstanceId(), WorkflowTaskEntity.Status.PENDING)) {
                t.setStatus(WorkflowTaskEntity.Status.CANCELED);
                t.setFinishedAt(Instant.now());
                taskRepository.save(t);
            }
        }
        r.setStatus(ApprovalRequestEntity.STATUS_CANCELED);
        r.setFinishedAt(Instant.now());
        requestRepository.save(r);
        indexApproval(r);
        return detailOf(r);
    }

    // ============================================================
    //  状态同步（单一事实 = workflow_instance.status）
    // ============================================================

    /**
     * 把 {@code workflow_instance.status} 投影到审批状态。
     *
     * @param instance 可传 null（内部按 instanceId 查）
     */
    public ApprovalRequestEntity syncStatus(ApprovalRequestEntity request,
                                            WorkflowInstanceEntity instanceOrNull) {
        if (request.getInstanceId() == null) return request;
        WorkflowInstanceEntity instance = instanceOrNull != null
                ? instanceOrNull
                : instanceRepository.findById(request.getInstanceId()).orElse(null);
        if (instance == null) return request;
        // 归属校验：实例租户必须与单据租户一致，否则视为脏数据（跨租户越权）
        if (instance.getTenantId() != null
                && !request.getTenantId().equals(instance.getTenantId())) {
            log.error("[approval] 单据与实例租户不一致，忽略 instance={} request={}",
                    instance.getId(), request.getId());
            return request;
        }

        String mapped = mapStatus(instance.getStatus());
        if (!mapped.equals(request.getStatus())) {
            request.setStatus(mapped);
            if (!ApprovalRequestEntity.STATUS_PENDING.equals(mapped)) {
                request.setFinishedAt(instance.getFinishedAt() != null
                        ? instance.getFinishedAt() : Instant.now());
            }
            requestRepository.save(request);
            indexApproval(request);
        }
        // 当前待办任务
        List<WorkflowTaskEntity> pending = taskRepository.findByInstanceIdAndStatus(
                request.getInstanceId(), WorkflowTaskEntity.Status.PENDING);
        UUID currentTaskId = pending.isEmpty() ? null : pending.get(0).getId();
        if (!java.util.Objects.equals(currentTaskId, request.getCurrentTaskId())) {
            request.setCurrentTaskId(currentTaskId);
            requestRepository.save(request);
        }
        return request;
    }

    private static String mapStatus(WorkflowInstanceEntity.Status s) {
        return switch (s) {
            case COMPLETED -> ApprovalRequestEntity.STATUS_APPROVED;
            case FAILED -> ApprovalRequestEntity.STATUS_REJECTED;
            case TERMINATED -> ApprovalRequestEntity.STATUS_CANCELED;
            default -> ApprovalRequestEntity.STATUS_PENDING;
        };
    }

    /** E4/T5.3：审批单据写入统一索引（提交/状态变更时更新）。 */
    private void indexApproval(ApprovalRequestEntity request) {
        try {
            String templateName = templateRepository.findById(request.getTemplateId())
                    .map(ApprovalTemplateEntity::getName).orElse("审批单");
            String title = templateName + " - " + request.getStatus();
            String content = templateName + " " + request.getStatus() + " "
                    + request.getFormDataJson();
            Map<String, Object> meta = new HashMap<>();
            meta.put("collection", "approval_request");
            meta.put("recordId", request.getId().toString());
            meta.put("status", request.getStatus());
            searchService.indexEntity("approval", request.getId().toString(),
                    request.getTenantId(), title, content, meta);
        } catch (Exception e) {
            log.warn("[approval] 审批索引写入失败 request={}: {}", request.getId(), e.getMessage());
        }
    }

    private void notifyPendingTasks(WorkflowInstanceEntity instance, UUID requestId, String templateName) {
        try {
            List<WorkflowTaskEntity> pending = taskRepository.findByInstanceIdAndStatus(
                    instance.getId(), WorkflowTaskEntity.Status.PENDING);
            String deeplink = "/approvals/" + requestId;
            for (WorkflowTaskEntity task : pending) {
                if (task.getAssignee() == null) continue;
                notifyDispatcher.notifyOne(com.nocobase.notification.NotifyDispatcher.APPROVAL_TODO,
                        task.getAssignee(),
                        new com.nocobase.notification.NotifyDispatcher.Payload(
                                instance.getTenantId(),
                                "待你审批：" + templateName,
                                "有一条新的审批待办",
                                requestId.toString(), deeplink, "HIGH",
                                "approval-todo:" + task.getId()));
            }
        } catch (Exception e) {
            log.warn("[approval] 待办通知失败 request={}: {}", requestId, e.getMessage());
        }
    }

    // ============================================================
    //  DTO
    // ============================================================

    private Map<String, Object> summaryOf(ApprovalRequestEntity r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getId().toString());
        m.put("templateId", r.getTemplateId().toString());
        m.put("applicantId", r.getApplicantId().toString());
        m.put("status", r.getStatus());
        m.put("submittedAt", r.getSubmittedAt() == null ? null : r.getSubmittedAt().toString());
        m.put("finishedAt", r.getFinishedAt() == null ? null : r.getFinishedAt().toString());
        m.put("currentTaskId", r.getCurrentTaskId() == null ? null : r.getCurrentTaskId().toString());
        m.put("formData", parseJson(r.getFormDataJson()));
        templateRepository.findById(r.getTemplateId()).ifPresent(t -> {
            m.put("templateName", t.getName());
            m.put("icon", t.getIcon());
            m.put("category", t.getCategory());
        });
        return m;
    }

    private Map<String, Object> detailOf(ApprovalRequestEntity r) {
        Map<String, Object> m = summaryOf(r);
        List<Map<String, Object>> trail = new ArrayList<>();
        if (r.getInstanceId() != null) {
            for (WorkflowTaskEntity t : taskRepository.findByInstanceId(r.getInstanceId())) {
                Map<String, Object> step = new HashMap<>();
                step.put("taskId", t.getId().toString());
                step.put("nodeId", t.getNodeId());
                step.put("assignee", t.getAssignee() == null ? null : t.getAssignee().toString());
                step.put("status", t.getStatus().name());
                step.put("comment", t.getComment());
                step.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
                step.put("finishedAt", t.getFinishedAt() == null ? null : t.getFinishedAt().toString());
                trail.add(step);
            }
        }
        m.put("trail", trail);
        m.put("cc", ccRepository.findByRequestId(r.getId()).stream()
                .map(c -> c.getUserId().toString()).toList());
        return m;
    }

    // ============================================================
    //  工具
    // ============================================================

    private static String toJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
