package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工作流 API(US-401~407) — Week 11 MVP.
 *
 * 简化:同步执行,只 MANUAL 触发,只支持 APPROVAL 节点 + SINGLE 模式.
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "Workflows", description = "工作流")
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowRepository workflowRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowEngine engine;
    private final com.nocobase.audit.AuditService auditService;
    /** Week 42 D4b.2 — R08 死循环防护:静态图校验器。 */
    private final WorkflowGraphValidator graphValidator;

    public WorkflowController(
            WorkflowRepository workflowRepository,
            WorkflowInstanceRepository instanceRepository,
            WorkflowTaskRepository taskRepository,
            ObjectMapper objectMapper,
            WorkflowEngine engine,
            com.nocobase.audit.AuditService auditService,
            WorkflowGraphValidator graphValidator
    ) {
        this.workflowRepository = workflowRepository;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.auditService = auditService;
        this.graphValidator = graphValidator;
    }

    // ============================================================
    //  Workflow CRUD(US-401)
    // ============================================================

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String collection) {
        // Week 41 D6 G1:从 TenantContext 取当前租户(替代硬编码 "tenant_default")
        String tenant = TenantContext.currentTenantId();
        List<WorkflowEntity> ws = (collection != null && !collection.isBlank())
                ? workflowRepository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(collection, tenant)
                : workflowRepository.findByTenantIdOrderByCreatedAtDesc(tenant);
        return Map.of("code", 0, "message", "success",
                "data", ws.stream().map(this::toDto).toList());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        WorkflowEntity w = mustGet(id);
        Map<String, Object> dto = toDto(w);
        try {
            dto.put("nodes", objectMapper.readValue(w.getNodesJson(), new TypeReference<List<Map<String, Object>>>() {}));
            dto.put("edges", objectMapper.readValue(w.getEdgesJson(), new TypeReference<List<Map<String, Object>>>() {}));
            dto.put("trigger", objectMapper.readValue(w.getTriggerJson(), Map.class));
        } catch (Exception e) {
            dto.put("nodes", List.of());
            dto.put("edges", List.of());
            dto.put("trigger", Map.of());
        }
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody @Valid CreateWorkflowRequest req,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        // Week 42 D4b.2: 静态图校验 — 拒绝保存含环的工作流(R08 死循环防护)
        validateGraphOrThrow(req.nodes(), req.edges());

        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName(req.name());
        w.setTitle(req.title() != null ? req.title() : req.name());
        w.setDescription(req.description());
        w.setCollectionName(req.collectionName());
        w.setTriggerJson(req.trigger() != null ? req.trigger() : "{\"type\":\"manual\"}");
        w.setNodesJson(req.nodes() != null ? req.nodes() : "[]");
        w.setEdgesJson(req.edges() != null ? req.edges() : "[]");
        w.setEnabled(req.enabled() == null || req.enabled());
        w.setTenantId(TenantContext.currentTenantId());
        w.setCreatedAt(Instant.now());
        w.setCreatedBy(user.userId());
        WorkflowEntity saved = workflowRepository.save(w);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", toDto(saved)));
    }

    /**
     * 更新工作流(Week 41 B2 修复 — 前端编辑保存此前必失败).
     *
     * <p>允许修改:name / title / description / collectionName / enabled / trigger / nodes / edges.
     * 不可修改:id / tenantId / createdAt / createdBy(由 createdBy 决定归属).
     */
    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateWorkflowRequest req,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        WorkflowEntity w = mustGet(id);
        // 租户校验:跨租户不可改(Week 41 复核:改用 TenantContext,不再硬编码 tenant_default)
        String tenant = TenantContext.currentTenantId();
        if (!tenant.equals(w.getTenantId()) || !tenant.equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "跨租户不可修改");
        }
        if (req.name() != null && !req.name().isBlank()) w.setName(req.name());
        if (req.title() != null) w.setTitle(req.title());
        if (req.description() != null) w.setDescription(req.description());
        if (req.collectionName() != null && !req.collectionName().isBlank()) w.setCollectionName(req.collectionName());
        if (req.trigger() != null) w.setTriggerJson(req.trigger());
        // Week 42 D4b.2: 仅当 nodes/edges 实际被改时校验,避免无意义重读+重序列化
        if (req.nodes() != null) {
            w.setNodesJson(req.nodes());
            validateGraphOrThrow(req.nodes(), w.getEdgesJson());
        }
        if (req.edges() != null) {
            w.setEdgesJson(req.edges());
            validateGraphOrThrow(w.getNodesJson(), req.edges());
        }
        if (req.enabled() != null) w.setEnabled(req.enabled());
        WorkflowEntity saved = workflowRepository.save(w);
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "UPDATE", "workflow", w.getId().toString(),
                Map.of("name", saved.getName()));
        return Map.of("code", 0, "message", "success", "data", toDto(saved));
    }

    /**
     * 删除工作流(Week 41 B2 修复).
     *
     * <p>删除策略(报告 3.2):拒绝删除有活跃实例(RUNNING / PENDING)的工作流。
     * 用户需先 disable 工作流,等待所有实例自然走完(COMPLETED / FAILED / CANCELED)后再删。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        WorkflowEntity w = mustGet(id);
        String tenant = TenantContext.currentTenantId();
        if (!tenant.equals(w.getTenantId()) || !tenant.equals(user.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "跨租户不可删除");
        }
        // 检查活跃实例
        List<WorkflowInstanceEntity> active = instanceRepository.findByWorkflowIdAndStatusIn(
                id, List.of(WorkflowInstanceEntity.Status.RUNNING, WorkflowInstanceEntity.Status.PENDING));
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "工作流有 " + active.size() + " 个运行中实例,请先禁用并等待完成后再删");
        }
        // 记录审计后再删
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "DELETE", "workflow", id.toString(),
                Map.of("name", w.getName()));
        workflowRepository.delete(w);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/{id}/trigger")
    @Transactional
    public ResponseEntity<Map<String, Object>> trigger(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> payload,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.nocobase.auth.JwtAuthFilter.AuthenticatedUser user
    ) {
        WorkflowEntity w = mustGet(id);
        if (!w.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流未启用");
        }

        // 1. 创建实例
        WorkflowInstanceEntity instance = new WorkflowInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setWorkflowId(w.getId());
        instance.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        try {
            instance.setTriggerDataJson(payload != null
                    ? objectMapper.writeValueAsString(payload)
                    : "{}");
        } catch (Exception e) {
            instance.setTriggerDataJson("{}");
        }
        if (payload != null && payload.get("record_id") != null) {
            instance.setRecordId(payload.get("record_id").toString());
        }
        instance.setStartedAt(Instant.now());
        instance.setCurrentNodeIndex(0);
        instance.setTenantId(TenantContext.currentTenantId());
        instance = instanceRepository.save(instance);

        // 2. 解析节点 + edges(Week 14:支持图遍历)
        List<Map<String, Object>> nodes;
        List<Map<String, Object>> edges;
        try {
            nodes = objectMapper.readValue(w.getNodesJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
            edges = objectMapper.readValue(w.getEdgesJson(),
                    new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            nodes = List.of();
            edges = List.of();
        }

        // 3. 优先图遍历(若 edges 非空);否则回退数组顺序
        WorkflowEngine.NodeResult result;
        if (!edges.isEmpty() && !nodes.isEmpty()) {
            String startId = (String) nodes.get(0).get("id");
            result = engine.executeGraphFrom(instance, nodes, edges, startId, w.getCreatedBy());
        } else {
            result = engine.executeFrom(instance, nodes, 0, w.getCreatedBy());
        }

        if (result == WorkflowEngine.NodeResult.NEEDS_APPROVAL) {
            auditService.log(user.tenantId(), user.userId(), user.username(),
                    "TRIGGER", "workflow", w.getId().toString(),
                    Map.of("instance", instance.getId().toString(), "result", "PENDING"));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "code", 0, "message", "workflow waiting for approval",
                    "data", instanceToDto(instance)
            ));
        }

        String msg = result == WorkflowEngine.NodeResult.FAILED ? "workflow failed" : "workflow completed";
        auditService.log(user.tenantId(), user.userId(), user.username(),
                "TRIGGER", "workflow", w.getId().toString(),
                Map.of("instance", instance.getId().toString(), "result", instance.getStatus().name()));
        return ResponseEntity.ok(Map.of(
                "code", result == WorkflowEngine.NodeResult.FAILED ? 500 : 0,
                "message", msg,
                "data", instanceToDto(instance)
        ));
    }

    // ============================================================
    //  Instance + Tasks(US-407, US-408)
    // ============================================================

    @GetMapping("/instances")
    public Map<String, Object> listInstances(@RequestParam(required = false) UUID workflowId) {
        // Week 41 D6 G1:从 TenantContext 取当前租户
        String tenant = TenantContext.currentTenantId();
        List<WorkflowInstanceEntity> list = (workflowId != null)
                ? instanceRepository.findByWorkflowIdAndTenantIdOrderByStartedAtDesc(workflowId, tenant)
                : instanceRepository.findByTenantIdOrderByStartedAtDesc(tenant);
        return Map.of("code", 0, "message", "success",
                "data", list.stream().map(this::instanceToDto).toList());
    }

    @GetMapping("/instances/{id}")
    public Map<String, Object> getInstance(@PathVariable UUID id) {
        WorkflowInstanceEntity i = instanceRepository.findByIdAndTenantId(id, TenantContext.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实例不存在"));
        Map<String, Object> dto = instanceToDto(i);
        dto.put("tasks", taskRepository.findByInstanceId(id).stream()
                .map(this::taskToDto).toList());
        return Map.of("code", 0, "message", "success", "data", dto);
    }

    // ============================================================
    //  Task Approval(US-408 待办 + 审批)
    // ============================================================

    @GetMapping("/tasks/my")
    public Map<String, Object> myTasks(@AuthenticationPrincipal AuthenticatedUser user) {
        List<WorkflowTaskEntity> tasks = taskRepository.findByAssigneeAndStatus(
                user.userId(), WorkflowTaskEntity.Status.PENDING);
        return Map.of("code", 0, "message", "success",
                "data", tasks.stream().map(this::taskToDto).toList());
    }

    @PostMapping("/tasks/{id}/approve")
    @Transactional
    public Map<String, Object> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        WorkflowTaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task 不存在"));
        if (task.getStatus() != WorkflowTaskEntity.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task 已处理");
        }

        task.setStatus(WorkflowTaskEntity.Status.APPROVED);
        task.setComment(body != null ? body.get("comment") : null);
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
                return Map.of("code", 0,
                        "message", "approved, waiting for other approvers ("
                                + pendingSiblings.size() + " pending)",
                        "data", instanceToDto(waiting));
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
                nextTask.setNodeId((String) node.get("id"));
                nextTask.setNodeType("APPROVAL");
                nextTask.setAssignee(w.getCreatedBy());
                nextTask.setStatus(WorkflowTaskEntity.Status.PENDING);
                nextTask.setCreatedAt(Instant.now());
                taskRepository.save(nextTask);

                instance.setStatus(WorkflowInstanceEntity.Status.PENDING);
                instanceRepository.save(instance);
                return Map.of("code", 0, "message", "approved, next node waiting",
                        "data", instanceToDto(instance));
            } else if ("NOTIFICATION".equals(nodeType)) {
                System.out.println("[workflow " + w.getId() + " node " + i + "] notification: " + node.get("config"));
            }
        }

        // 全部跑完
        instance.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
        instance.setFinishedAt(Instant.now());
        instanceRepository.save(instance);

        return Map.of("code", 0, "message", "workflow completed",
                "data", instanceToDto(instance));
    }

    @PostMapping("/tasks/{id}/reject")
    @Transactional
    public Map<String, Object> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body
    ) {
        WorkflowTaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task 不存在"));
        if (task.getStatus() != WorkflowTaskEntity.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Task 已处理");
        }
        task.setStatus(WorkflowTaskEntity.Status.REJECTED);
        task.setComment(body != null ? body.get("comment") : null);
        task.setFinishedAt(Instant.now());
        taskRepository.save(task);

        WorkflowInstanceEntity instance = instanceRepository.findById(task.getInstanceId()).orElseThrow();
        instance.setStatus(WorkflowInstanceEntity.Status.FAILED);
        instance.setErrorMessage("用户在节点 " + task.getNodeId() + " 拒绝");
        instance.setFinishedAt(Instant.now());
        instanceRepository.save(instance);

        WorkflowEntity wf = workflowRepository.findById(instance.getWorkflowId()).orElse(null);
        auditService.log(TenantContext.currentTenantId(), task.getAssignee(), null,
                "REJECT", "workflow_task", task.getId().toString(),
                Map.of("workflow", wf != null ? wf.getId().toString() : "", "comment", task.getComment() == null ? "" : task.getComment()));

        return Map.of("code", 0, "message", "workflow rejected",
                "data", instanceToDto(instance));
    }

    // ============================================================
    //  DTOs
    // ============================================================

    private WorkflowEntity mustGet(UUID id) {
        // Week 41 复核:改用 TenantContext(mustGet 无租户参数,非默认租户此前永远 404)
        return workflowRepository.findByIdAndTenantId(id, TenantContext.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在"));
    }

    /**
     * Week 42 D4b.2 — R08 死循环防护第 1 道:
     * <ol>
     *   <li>解析 JSON,任何节点/边结构异常都会被捕获 → 400</li>
     *   <li>调用 {@link WorkflowGraphValidator},环/超限/孤儿边 → 400 + 描述</li>
     * </ol>
     */
    private void validateGraphOrThrow(Object nodesJson, Object edgesJson) {
        java.util.List<java.util.Map<String, Object>> nodes;
        java.util.List<java.util.Map<String, Object>> edges;
        try {
            nodes = nodesJson == null ? java.util.List.of()
                    : objectMapper.readValue(
                            nodesJson.toString(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
            edges = edgesJson == null ? java.util.List.of()
                    : objectMapper.readValue(
                            edgesJson.toString(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "节点或边 JSON 解析失败: " + e.getMessage());
        }
        try {
            graphValidator.validate(nodes, edges);
        } catch (WorkflowGraphValidationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "工作流图校验失败(R08): " + e.getMessage());
        }
    }

    private Map<String, Object> toDto(WorkflowEntity w) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", w.getId().toString());
        dto.put("name", w.getName());
        dto.put("title", w.getTitle());
        dto.put("description", w.getDescription() != null ? w.getDescription() : "");
        dto.put("collection_name", w.getCollectionName());
        dto.put("trigger_json", w.getTriggerJson());
        dto.put("nodes_json", w.getNodesJson());
        dto.put("edges_json", w.getEdgesJson());
        dto.put("enabled", w.isEnabled());
        dto.put("tenant_id", w.getTenantId());
        dto.put("created_at", w.getCreatedAt().toString());
        return dto;
    }

    private Map<String, Object> instanceToDto(WorkflowInstanceEntity i) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", i.getId().toString());
        dto.put("workflow_id", i.getWorkflowId().toString());
        dto.put("status", i.getStatus().name());
        dto.put("record_id", i.getRecordId());
        dto.put("current_node_index", i.getCurrentNodeIndex());
        dto.put("error_message", i.getErrorMessage());
        dto.put("trigger_data_json", i.getTriggerDataJson());
        dto.put("started_at", i.getStartedAt() != null ? i.getStartedAt().toString() : null);
        dto.put("finished_at", i.getFinishedAt() != null ? i.getFinishedAt().toString() : null);
        // 关联 workflow 名称/标题(避免前端 N+1)
        workflowRepository.findById(i.getWorkflowId()).ifPresent(w -> {
            dto.put("workflow_name", w.getName());
            dto.put("workflow_title", w.getTitle());
        });
        return dto;
    }

    private Map<String, Object> taskToDto(WorkflowTaskEntity t) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", t.getId().toString());
        dto.put("instance_id", t.getInstanceId().toString());
        dto.put("node_id", t.getNodeId());
        dto.put("node_type", t.getNodeType());
        dto.put("assignee", t.getAssignee() != null ? t.getAssignee().toString() : null);
        dto.put("status", t.getStatus().name());
        dto.put("comment", t.getComment());
        dto.put("created_at", t.getCreatedAt().toString());
        dto.put("finished_at", t.getFinishedAt() != null ? t.getFinishedAt().toString() : null);
        // 关联 instance → workflow 信息
        instanceRepository.findById(t.getInstanceId()).ifPresent(inst -> {
            dto.put("trigger_data_json", inst.getTriggerDataJson());
            dto.put("record_id", inst.getRecordId());
            dto.put("instance_status", inst.getStatus().name());
            workflowRepository.findById(inst.getWorkflowId()).ifPresent(w -> {
                dto.put("workflow_id", w.getId().toString());
                dto.put("workflow_name", w.getName());
                dto.put("workflow_title", w.getTitle());
            });
        });
        return dto;
    }

    public record CreateWorkflowRequest(
            @NotBlank String name,
            String title,
            String description,
            @NotBlank String collectionName,
            String trigger,
            String nodes,
            String edges,
            Boolean enabled
    ) {}

    /**
     * Week 41 B2:所有字段可选(PATCH 语义),null 表示不修改。
     * 强制约束:name / collectionName 非空且非 blank 时才覆盖。
     */
    public record UpdateWorkflowRequest(
            String name,
            String title,
            String description,
            String collectionName,
            String trigger,
            String nodes,
            String edges,
            Boolean enabled
    ) {}
}
