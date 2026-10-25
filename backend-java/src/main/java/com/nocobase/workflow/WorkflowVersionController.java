package com.nocobase.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工作流版本管理 API (US-410)。
 *
 * <ul>
 *   <li>GET  /api/workflows/{id}/versions            版本列表(倒序)</li>
 *   <li>POST /api/workflows/{id}/versions/publish   发布当前定义为新版本</li>
 *   <li>POST /api/workflows/{id}/versions/{v}/rollback  回滚到指定版本</li>
 * </ul>
 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name = "WorkflowVersions", description = "工作流版本管理")
@RequestMapping("/api/workflows/{workflowId}/versions")
public class WorkflowVersionController {

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowGraphValidator graphValidator;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public WorkflowVersionController(
            WorkflowRepository workflowRepository,
            WorkflowVersionRepository versionRepository,
            WorkflowGraphValidator graphValidator,
            ObjectMapper objectMapper,
            AuditService auditService
    ) {
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.graphValidator = graphValidator;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public Map<String, Object> list(@PathVariable UUID workflowId) {
        WorkflowEntity w = mustGet(workflowId);
        List<Map<String, Object>> versions = versionRepository
                .findByWorkflowIdAndTenantIdOrderByVersionDesc(workflowId, w.getTenantId())
                .stream().map(this::toSummary).toList();
        return Map.of("code", 0, "message", "success",
                "data", Map.of(
                        "workflow_id", workflowId.toString(),
                        "current_version", versionRepository
                                .findMaxVersionByWorkflowIdAndTenantId(workflowId, w.getTenantId()),
                        "versions", versions));
    }

    /**
     * 发布当前工作流定义为新版本（版本号自增）。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/publish")
    @Transactional
    public ResponseEntity<Map<String, Object>> publish(
            @PathVariable UUID workflowId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        WorkflowEntity w = mustGet(workflowId);
        int next = versionRepository.findMaxVersionByWorkflowIdAndTenantId(workflowId, w.getTenantId()) + 1;

        WorkflowVersionEntity v = new WorkflowVersionEntity();
        v.setId(UUID.randomUUID());
        v.setTenantId(w.getTenantId());
        v.setWorkflowId(workflowId);
        v.setVersion(next);
        v.setDefinitionJson(snapshotOf(w));
        v.setPublishedBy(user.userId());
        v.setPublishedAt(Instant.now());
        WorkflowVersionEntity saved = versionRepository.save(v);

        auditService.log(user.tenantId(), user.userId(), user.username(),
                "PUBLISH", "workflow_version", saved.getId().toString(),
                Map.of("workflow_id", workflowId.toString(), "version", next));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "success", "data", toSummary(saved)));
    }

    /**
     * 回滚到指定版本：用快照覆盖当前工作流定义。
     *
     * <p>回滚前重新校验快照图（防止历史脏数据），成功后写入审计日志。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{version}/rollback")
    @Transactional
    public Map<String, Object> rollback(
            @PathVariable UUID workflowId,
            @PathVariable Integer version,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        WorkflowEntity w = mustGet(workflowId);
        WorkflowVersionEntity v = versionRepository
                .findByWorkflowIdAndVersionAndTenantId(workflowId, version, w.getTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在"));

        Map<String, Object> snapshot = parseSnapshot(v.getDefinitionJson());

        String nodes = str(snapshot.get("nodes"));
        String edges = str(snapshot.get("edges"));
        // 回滚前重新校验图结构，拒绝坏快照
        validateGraphOrThrow(nodes, edges);

        String beforeName = w.getName();
        if (snapshot.get("name") instanceof String n && !n.isBlank()) w.setName(n);
        if (snapshot.get("title") instanceof String t) w.setTitle(t);
        if (snapshot.get("description") instanceof String d) w.setDescription(d);
        if (snapshot.get("trigger") != null) w.setTriggerJson(str(snapshot.get("trigger")));
        w.setNodesJson(nodes);
        w.setEdgesJson(edges);
        WorkflowEntity saved = workflowRepository.save(w);

        auditService.log(user.tenantId(), user.userId(), user.username(),
                "ROLLBACK", "workflow", workflowId.toString(),
                Map.of("version", version, "before_name", beforeName, "after_name", saved.getName()));

        return Map.of("code", 0, "message", "success",
                "data", Map.of(
                        "workflow_id", workflowId.toString(),
                        "rolled_back_to", version,
                        "name", saved.getName()));
    }

    // ------------------------------------------------------------

    private WorkflowEntity mustGet(UUID workflowId) {
        return workflowRepository.findByIdAndTenantId(workflowId, TenantContext.currentTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在"));
    }

    /** 构造当前定义的 JSON 快照。 */
    private String snapshotOf(WorkflowEntity w) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("name", w.getName());
        snap.put("title", w.getTitle());
        snap.put("description", w.getDescription());
        snap.put("trigger", w.getTriggerJson());
        snap.put("nodes", w.getNodesJson());
        snap.put("edges", w.getEdgesJson());
        try {
            return objectMapper.writeValueAsString(snap);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "序列化工作流快照失败: " + e.getMessage());
        }
    }

    private Map<String, Object> parseSnapshot(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "解析版本快照失败: " + e.getMessage());
        }
    }

    private void validateGraphOrThrow(String nodesJson, String edgesJson) {
        List<Map<String, Object>> nodes;
        List<Map<String, Object>> edges;
        try {
            nodes = nodesJson == null ? List.of()
                    : objectMapper.readValue(nodesJson, new TypeReference<List<Map<String, Object>>>() {});
            edges = edgesJson == null ? List.of()
                    : objectMapper.readValue(edgesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "节点或边 JSON 解析失败: " + e.getMessage());
        }
        try {
            graphValidator.validate(nodes, edges);
        } catch (WorkflowGraphValidationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流图校验失败(R08): " + e.getMessage());
        }
    }

    /**
     * 将快照字段序列化为 JSON 文本。
     *
     * <p>注意：不能使用 {@code Object.toString()}，因为 List/Map 的 toString 产出的是
     * Java 风格字面量（如 {@code [{id=n1}]}），并非合法 JSON，会导致回滚时解析失败。
     */
    private String str(Object o) {
        if (o == null) {
            return "[]";
        }
        if (o instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "快照序列化失败: " + e.getMessage());
        }
    }

    private Map<String, Object> toSummary(WorkflowVersionEntity v) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", v.getId().toString());
        dto.put("workflow_id", v.getWorkflowId().toString());
        dto.put("version", v.getVersion());
        dto.put("published_by", v.getPublishedBy() != null ? v.getPublishedBy().toString() : null);
        dto.put("published_at", v.getPublishedAt().toString());
        // 摘要：节点数量，便于前端展示
        try {
            List<Object> nodes = objectMapper.readValue(
                    String.valueOf(parseSnapshot(v.getDefinitionJson()).get("nodes")),
                    new TypeReference<List<Object>>() {});
            dto.put("node_count", nodes.size());
        } catch (Exception e) {
            dto.put("node_count", 0);
        }
        return dto;
    }
}
