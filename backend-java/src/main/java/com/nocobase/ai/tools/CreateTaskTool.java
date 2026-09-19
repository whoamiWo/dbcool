package com.nocobase.ai.tools;

import com.nocobase.ai.AgentTool;
import com.nocobase.ai.AgentToolContext;
import com.nocobase.project.ProjectService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * create_task 工具 — 接真：委托 ProjectService 创建项目任务。
 */
@Component
public class CreateTaskTool implements AgentTool {

    private final ProjectService projectService;

    public CreateTaskTool(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override public String name() { return "create_task"; }
    @Override public String description() { return "在项目下创建任务"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> params, AgentToolContext ctx) {
        UUID projectId = parseOptionalUuid(params, "projectId");
        String title = (String) params.get("title");
        if (projectId == null) return Map.of("error", "projectId 必填");
        if (title == null || title.isBlank()) return Map.of("error", "title 必填");
        try {
            var task = projectService.create(projectId, title,
                    (String) params.get("description"),
                    parseOptionalUuid(params, "parentId"),
                    parseOptionalUuid(params, "assigneeId"),
                    (String) params.get("status"),
                    (String) params.get("priority"),
                    parseOptionalInstant(params, "startDate"),
                    parseOptionalInstant(params, "endDate"),
                    ctx.userId(), ctx.tenantId());
            return Map.of("taskId", task.getId().toString(), "title", task.getTitle(),
                          "status", task.getStatus());
        } catch (Exception e) {
            return Map.of("error", "创建失败: " + e.getMessage());
        }
    }

    private static UUID parseOptionalUuid(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        try { return UUID.fromString(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static Instant parseOptionalInstant(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        try { return Instant.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
