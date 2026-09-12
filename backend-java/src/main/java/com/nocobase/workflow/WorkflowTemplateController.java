package com.nocobase.workflow;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 工作流模板市场 API(US-410).
 *
 * <p>3 个内置模板:
 * <ul>
 *   <li>leave_approval — 请假审批</li>
 *   <li>expense_report — 报销审批</li>
 *   <li>customer_followup — 新客户跟进</li>
 * </ul>
 *
 * <p>端点:
 * <ul>
 *   <li>GET /api/workflow/templates — 列出所有可用模板</li>
 *   <li>GET /api/workflow/templates/{key} — 模板详情</li>
 *   <li>POST /api/workflow/templates/{key}/install — 一键安装</li>
 * </ul>
 */
@RestController
@Tag(name = "Workflow Templates", description = "工作流模板市场")
@RequestMapping("/api/workflow/templates")
public class WorkflowTemplateController {

    private final WorkflowTemplateRegistry registry;
    private final WorkflowTemplateService installer;

    public WorkflowTemplateController(WorkflowTemplateRegistry registry,
                                      WorkflowTemplateService installer) {
        this.registry = registry;
        this.installer = installer;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> data = registry.list().stream()
                .map(this::summary)
                .toList();
        return Map.of("code", 0, "message", "success",
                "data", Map.of("templates", data, "total", data.size()));
    }

    @GetMapping("/{key}")
    public Map<String, Object> get(@PathVariable String key) {
        WorkflowTemplate t = registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "模板不存在: " + key));
        return Map.of("code", 0, "message", "success", "data", t);
    }

    @PostMapping("/{key}/install")
    public ResponseEntity<Map<String, Object>> install(
            @PathVariable String key,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Map<String, Object> result = installer.install(user.tenantId(), key, user.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("code", 0, "message", "installed", "data", result));
    }

    private Map<String, Object> summary(WorkflowTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", t.key());
        m.put("name", t.name());
        m.put("category", t.category());
        m.put("description", t.description());
        m.put("icon", t.icon());
        m.put("collections_count", t.collections().size());
        m.put("nodes_count", t.workflow().nodes().size());
        return m;
    }
}
