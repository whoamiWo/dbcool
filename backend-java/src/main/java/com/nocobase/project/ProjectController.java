package com.nocobase.project;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目任务 REST API — 看板与甘特图数据源。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** 项目列表。 */
    @GetMapping
    public Map<String, Object> listProjects(@AuthenticationPrincipal AuthenticatedUser user) {
        List<ProjectEntity> projects = projectService.listByTenant(user.tenantId());
        return Map.of("code", 0, "message", "success",
                "data", projects.stream().map(this::projectToDto).toList(),
                "total", projects.size());
    }

    /** 创建项目：body = {name, description}. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createProject(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "项目名称必填");
        }
        String description = (String) body.get("description");
        ProjectEntity project = projectService.create(name, description, user.userId(), user.tenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", projectToDto(project)));
    }

    /** 创建任务:body = {projectId, title, description, parentId, assigneeId, status, priority, startDate, endDate} */
    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> createTask(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String projectIdStr = (String) body.get("projectId");
        if (projectIdStr == null || projectIdStr.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "projectId 必填");
        }
        ProjectTaskEntity task = projectService.create(
                UUID.fromString(projectIdStr),
                (String) body.get("title"),
                (String) body.get("description"),
                uuidOrNull(body.get("parentId")),
                uuidOrNull(body.get("assigneeId")),
                (String) body.get("status"),
                (String) body.get("priority"),
                instantOrNull(body.get("startDate")),
                instantOrNull(body.get("endDate")),
                user.userId(),
                user.tenantId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", toDto(task)));
    }

    /** 项目任务列表(可传 status 过滤)。 */
    @GetMapping("/{projectId}/tasks")
    public Map<String, Object> listTasks(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String status,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<ProjectTaskEntity> tasks = status == null || status.isBlank()
                ? projectService.listByProject(projectId, user.tenantId())
                : projectService.listByStatus(projectId, status, user.tenantId());
        return Map.of("code", 0, "message", "success",
                "data", tasks.stream().map(this::toDto).toList(),
                "total", tasks.size());
    }

    /** 甘特图数据(含层级)。 */
    @GetMapping("/{projectId}/gantt")
    public Map<String, Object> gantt(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return Map.of("code", 0, "message", "success",
                "data", projectService.listGantt(projectId, user.tenantId()));
    }

    /** 我的任务。 */
    @GetMapping("/tasks/mine")
    public Map<String, Object> myTasks(@AuthenticationPrincipal AuthenticatedUser user) {
        List<ProjectTaskEntity> tasks = projectService.listMyTasks(user.userId(), user.tenantId());
        return Map.of("code", 0, "message", "success",
                "data", tasks.stream().map(this::toDto).toList(),
                "total", tasks.size());
    }

    /** 更新任务。 */
    @PutMapping("/tasks/{id}")
    public Map<String, Object> updateTask(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ProjectTaskEntity task = projectService.update(
                id,
                (String) body.get("title"),
                (String) body.get("description"),
                (String) body.get("status"),
                (String) body.get("priority"),
                uuidOrNull(body.get("assigneeId")),
                body.get("progress") instanceof Number n ? n.intValue() : null,
                instantOrNull(body.get("startDate")),
                instantOrNull(body.get("endDate")),
                user.userId(),
                user.tenantId()
        );
        return Map.of("code", 0, "message", "success", "data", toDto(task));
    }

    /** 删除任务。 */
    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> deleteTask(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        projectService.delete(id, user.tenantId());
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", id.toString()));
    }

    // ============================================================
    //  工具
    // ============================================================

    private Map<String, Object> toDto(ProjectTaskEntity t) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", t.getId().toString());
        m.put("projectId", t.getProjectId().toString());
        m.put("parentId", t.getParentId() == null ? null : t.getParentId().toString());
        m.put("title", t.getTitle());
        m.put("description", t.getDescription());
        m.put("status", t.getStatus());
        m.put("priority", t.getPriority());
        m.put("assigneeId", t.getAssigneeId() == null ? null : t.getAssigneeId().toString());
        m.put("startDate", t.getStartDate() == null ? null : t.getStartDate().toString());
        m.put("endDate", t.getEndDate() == null ? null : t.getEndDate().toString());
        m.put("progress", t.getProgress());
        m.put("sortOrder", t.getSortOrder());
        return m;
    }

    private Map<String, Object> projectToDto(ProjectEntity p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", p.getId().toString());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("ownerId", p.getOwnerId().toString());
        m.put("createdAt", p.getCreatedAt().toString());
        m.put("updatedAt", p.getUpdatedAt().toString());
        return m;
    }

    private static UUID uuidOrNull(Object v) {
        if (v == null) return null;
        try { return UUID.fromString(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static Instant instantOrNull(Object v) {
        if (v == null) return null;
        try { return Instant.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
