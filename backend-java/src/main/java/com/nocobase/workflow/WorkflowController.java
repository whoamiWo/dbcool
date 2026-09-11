package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public WorkflowController(
            WorkflowRepository workflowRepository,
            WorkflowInstanceRepository instanceRepository,
            WorkflowTaskRepository taskRepository,
            ObjectMapper objectMapper,
            WorkflowEngine engine,
            com.nocobase.audit.AuditService auditService
    ) {
        this.workflowRepository = workflowRepository;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.engine = engine;
        this.auditService = auditService;
    }

    // ============================================================
    //  Workflow CRUD(US-401)
    // ============================================================

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String collection) {
        List<WorkflowEntity> ws = (collection != null && !collection.isBlank())
                ? workflowRepository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(collection, "tenant_default")
                : workflowRepository.findByTenantIdOrderByCreatedAtDesc("tenant_default");
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
        w.setTenantId("tenant_default");
        w.setCreatedAt(Instant.now());
        w.setCreatedBy(user.userId());
        WorkflowEntity saved = workflowRepository.save(w);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", toDto(saved)));
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
        instance.setTenantId("tenant_default");
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
        List<WorkflowInstanceEntity> list = (workflowId != null)
                ? instanceRepository.findByWorkflowIdAndTenantIdOrderByStartedAtDesc(workflowId, "tenant_default")
                : instanceRepository.findByTenantIdOrderByStartedAtDesc("tenant_default");
        return Map.of("code", 0, "message", "success",
                "data", list.stream().map(this::instanceToDto).toList());
    }

    @GetMapping("/instances/{id}")
    public Map<String, Object> getInstance(@PathVariable UUID id) {
        WorkflowInstanceEntity i = instanceRepository.findByIdAndTenantId(id, "tenant_default")
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
        auditService.log("tenant_default", task.getAssignee(), null,
                "REJECT", "workflow_task", task.getId().toString(),
                Map.of("workflow", wf != null ? wf.getId().toString() : "", "comment", task.getComment() == null ? "" : task.getComment()));

        return Map.of("code", 0, "message", "workflow rejected",
                "data", instanceToDto(instance));
    }

    // ============================================================
    //  DTOs
    // ============================================================

    private WorkflowEntity mustGet(UUID id) {
        return workflowRepository.findByIdAndTenantId(id, "tenant_default")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在"));
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
}
