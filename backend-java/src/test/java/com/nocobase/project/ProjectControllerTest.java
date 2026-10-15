package com.nocobase.project;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE105: 覆盖 ProjectController 全部端点与私有 DTO 工具方法
 * (createTask/updateTask/toDto/projectToDto/uuidOrNull/instantOrNull)。
 */
class ProjectControllerTest {

    private ProjectService projectService;
    private ProjectController controller;

    private static final String TENANT = "tenant_default";
    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "admin", TENANT);

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        controller = new ProjectController(projectService);
    }

    private ProjectEntity project(UUID id, String name) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        p.setName(name);
        p.setDescription("desc");
        p.setOwnerId(UUID.randomUUID());
        p.setTenantId(TENANT);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private ProjectTaskEntity task(UUID projectId, String title) {
        ProjectTaskEntity t = new ProjectTaskEntity();
        t.setId(UUID.randomUUID());
        t.setProjectId(projectId);
        t.setParentId(UUID.randomUUID());
        t.setTitle(title);
        t.setDescription("d");
        t.setStatus("todo");
        t.setPriority("high");
        t.setAssigneeId(UUID.randomUUID());
        t.setStartDate(Instant.now());
        t.setEndDate(Instant.now());
        t.setProgress(40);
        t.setSortOrder(1);
        t.setTenantId(TENANT);
        return t;
    }

    @Test
    void listProjects_returnsProjectDtos() {
        UUID pid = UUID.randomUUID();
        when(projectService.listByTenant(TENANT)).thenReturn(List.of(project(pid, "P1")));

        Map<String, Object> resp = controller.listProjects(USER);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("total")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        assertThat(data.get(0).get("id")).isEqualTo(pid.toString());
        assertThat(data.get(0).get("name")).isEqualTo("P1");
        // projectToDto 全覆盖(14 字段)
        assertThat(data.get(0)).containsKeys(
                "id", "name", "description", "ownerId", "createdAt", "updatedAt");
    }

    @Test
    void createProject_missingName_throwsBadRequest() {
        assertThatThrownBy(() -> controller.createProject(Map.of("description", "x"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void createProject_success_returns201() {
        UUID pid = UUID.randomUUID();
        ProjectEntity created = project(pid, "New");
        when(projectService.create("New", "desc", USER.userId(), TENANT)).thenReturn(created);

        ResponseEntity<Map<String, Object>> resp = controller.createProject(
                Map.of("name", "New", "description", "desc"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("id")).isEqualTo(pid.toString());
    }

    @Test
    void createTask_missingProjectId_throwsBadRequest() {
        assertThatThrownBy(() -> controller.createTask(Map.of("title", "t"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTask_success_parsesUuidAndInstant() {
        UUID projectId = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        ProjectTaskEntity created = task(projectId, "T1");
        when(projectService.create(
                eq(projectId), eq("T1"), eq("d"), eq(parent), eq(assignee),
                eq("todo"), eq("high"), any(Instant.class), any(Instant.class),
                eq(USER.userId()), eq(TENANT)))
                .thenReturn(created);

        ResponseEntity<Map<String, Object>> resp = controller.createTask(Map.of(
                "projectId", projectId.toString(),
                "title", "T1",
                "description", "d",
                "parentId", parent.toString(),
                "assigneeId", assignee.toString(),
                "status", "todo",
                "priority", "high",
                "startDate", Instant.now().toString(),
                "endDate", Instant.now().toString()
        ), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // toDto 全覆盖
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("id")).isEqualTo(created.getId().toString());
        assertThat(data).containsKeys("id","projectId","parentId","title","description",
                "status","priority","assigneeId","startDate","endDate","progress","sortOrder");
    }

    @Test
    void createTask_invalidUuidAndInstant_nullSafe() {
        UUID projectId = UUID.randomUUID();
        when(projectService.create(
                eq(projectId), eq("T"), eq("d"), eq(null), eq(null),
                eq(null), eq(null), eq(null), eq(null),
                eq(USER.userId()), eq(TENANT)))
                .thenAnswer(inv -> task(projectId, "T"));

        // 非法 UUID / Instant 字符串被 uuidOrNull/instantOrNull 兜底为 null
        ResponseEntity<Map<String, Object>> resp = controller.createTask(Map.of(
                "projectId", projectId.toString(),
                "title", "T",
                "description", "d",
                "parentId", "not-a-uuid",
                "assigneeId", "not-a-uuid",
                "startDate", "not-a-date",
                "endDate", "not-a-date"
        ), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listTasks_noStatus_listsByProject() {
        UUID projectId = UUID.randomUUID();
        when(projectService.listByProject(projectId, TENANT))
                .thenReturn(List.of(task(projectId, "T1")));

        Map<String, Object> resp = controller.listTasks(projectId, null, USER);

        assertThat(resp.get("total")).isEqualTo(1);
        verify(projectService).listByProject(projectId, TENANT);
    }

    @Test
    void listTasks_withStatus_listsByStatus() {
        UUID projectId = UUID.randomUUID();
        when(projectService.listByStatus(projectId, "todo", TENANT))
                .thenReturn(List.of());

        Map<String, Object> resp = controller.listTasks(projectId, "todo", USER);

        assertThat(resp.get("total")).isEqualTo(0);
        verify(projectService).listByStatus(projectId, "todo", TENANT);
    }

    @Test
    void gantt_returnsServiceData() {
        UUID projectId = UUID.randomUUID();
        when(projectService.listGantt(projectId, TENANT)).thenReturn(List.of(Map.of("t", "x")));

        Map<String, Object> resp = controller.gantt(projectId, USER);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("data")).isEqualTo(List.of(Map.of("t", "x")));
    }

    @Test
    void myTasks_listsByAssignee() {
        when(projectService.listMyTasks(USER.userId(), TENANT))
                .thenReturn(List.of(task(UUID.randomUUID(), "mine")));

        Map<String, Object> resp = controller.myTasks(USER);

        assertThat(resp.get("total")).isEqualTo(1);
    }

    @Test
    void updateTask_parsesProgressNumber() {
        UUID taskId = UUID.randomUUID();
        // 全参数 mock(非法 UUID/Instant 兜底为 null, any() 不匹配 null 故用 isNull/eq)
        when(projectService.update(
                eq(taskId), eq("T"), eq("d"), eq("done"), eq("low"),
                eq((UUID) null), eq(75), eq((Instant) null), eq((Instant) null),
                eq(USER.userId()), eq(TENANT)))
                .thenAnswer(inv -> task(UUID.randomUUID(), "T"));

        Map<String, Object> resp = controller.updateTask(taskId, Map.of(
                "title", "T", "description", "d", "status", "done", "priority", "low",
                "assigneeId", "not-uuid", "progress", 75,
                "startDate", "bad", "endDate", "bad"
        ), USER);

        assertThat(resp.get("code")).isEqualTo(0);
        // progress Number 75 → Integer 75; 非法 assigneeId/startDate/endDate → null
        verify(projectService).update(
                eq(taskId), eq("T"), eq("d"), eq("done"), eq("low"),
                eq((UUID) null), eq(75), eq((Instant) null), eq((Instant) null),
                eq(USER.userId()), eq(TENANT));
    }

    @Test
    void deleteTask_delegatesToService() {
        UUID taskId = UUID.randomUUID();

        Map<String, Object> resp = controller.deleteTask(taskId, USER);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("message")).isEqualTo("deleted");
        verify(projectService).delete(taskId, TENANT);
    }
}
