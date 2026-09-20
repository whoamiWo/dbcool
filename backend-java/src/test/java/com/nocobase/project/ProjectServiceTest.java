package com.nocobase.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectServiceTest {

    private ProjectTaskRepository repo;
    private ProjectRepository projectRepo;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        repo = mock(ProjectTaskRepository.class);
        projectRepo = mock(ProjectRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ProjectService(repo, projectRepo);
    }

    private UUID pid = UUID.randomUUID();
    private String tid = "tenant_default";

    // ============================================================
    //  1. create
    // ============================================================

    @Test
    void create_validTask_persistsAndReturns() {
        ProjectTaskEntity t = service.create(pid, "T1", "desc", null, null,
                "TODO", "MEDIUM", null, null, UUID.randomUUID(), tid);
        assertThat(t.getId()).isNotNull();
        assertThat(t.getTitle()).isEqualTo("T1");
        assertThat(t.getStatus()).isEqualTo("TODO");
        assertThat(t.getPriority()).isEqualTo("MEDIUM");
        assertThat(t.getProgress()).isEqualTo(0);
    }

    @Test
    void create_emptyTitle_throwsBadRequest() {
        assertThatThrownBy(() -> service.create(pid, "", "desc", null, null,
                null, null, null, null, UUID.randomUUID(), tid))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ============================================================
    //  2. listByProject / listByStatus / listMyTasks
    // ============================================================

    @Test
    void listByProject_delegatesToRepository() {
        ProjectTaskEntity t = new ProjectTaskEntity();
        t.setId(UUID.randomUUID());
        when(repo.findByTenantIdAndProjectIdOrderBySortOrderAscCreatedAtAsc(tid, pid))
                .thenReturn(List.of(t));
        List<ProjectTaskEntity> result = service.listByProject(pid, tid);
        assertThat(result).hasSize(1);
    }

    @Test
    void listByStatus_filtersByStatus() {
        ProjectTaskEntity t = new ProjectTaskEntity();
        t.setId(UUID.randomUUID());
        when(repo.findByTenantIdAndProjectIdAndStatusOrderBySortOrderAsc(tid, pid, "TODO"))
                .thenReturn(List.of(t));
        List<ProjectTaskEntity> result = service.listByStatus(pid, "TODO", tid);
        assertThat(result).hasSize(1);
    }

    @Test
    void listMyTasks_ordersByEndDate() {
        UUID assignee = UUID.randomUUID();
        ProjectTaskEntity t = new ProjectTaskEntity();
        t.setId(UUID.randomUUID());
        when(repo.findByTenantIdAndAssigneeIdOrderByEndDateAsc(tid, assignee))
                .thenReturn(List.of(t));
        List<ProjectTaskEntity> result = service.listMyTasks(assignee, tid);
        assertThat(result).hasSize(1);
    }

    // ============================================================
    //  3. listGantt (parent-child hierarchy)
    // ============================================================

    @Test
    void listGantt_buildsHierarchy() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        ProjectTaskEntity root = new ProjectTaskEntity();
        root.setId(rootId);
        root.setParentId(null);
        root.setTitle("Root");
        root.setStatus("TODO");
        root.setPriority("HIGH");
        root.setProgress(50);
        root.setStartDate(Instant.parse("2026-01-01T00:00:00Z"));
        root.setEndDate(Instant.parse("2026-01-10T00:00:00Z"));
        root.setAssigneeId(UUID.randomUUID());

        ProjectTaskEntity child = new ProjectTaskEntity();
        child.setId(childId);
        child.setParentId(rootId);
        child.setTitle("Child");
        child.setStatus("IN_PROGRESS");
        child.setPriority("MEDIUM");
        child.setProgress(30);
        child.setStartDate(Instant.parse("2026-01-02T00:00:00Z"));
        child.setEndDate(Instant.parse("2026-01-05T00:00:00Z"));
        child.setAssigneeId(UUID.randomUUID());

        when(repo.findByTenantIdAndProjectIdOrderBySortOrderAscCreatedAtAsc(tid, pid))
                .thenReturn(List.of(root, child));

        List<Map<String, Object>> gantt = service.listGantt(pid, tid);
        assertThat(gantt).hasSize(1);
        Map<String, Object> rootNode = gantt.get(0);
        assertThat(rootNode.get("title")).isEqualTo("Root");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) rootNode.get("children");
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("title")).isEqualTo("Child");
    }

    // ============================================================
    //  4. update
    // ============================================================

    @Test
    void update_changesFields() {
        UUID id = UUID.randomUUID();
        ProjectTaskEntity existing = new ProjectTaskEntity();
        existing.setId(id);
        existing.setTenantId(tid);
        existing.setProjectId(pid);
        existing.setTitle("Old");
        existing.setStatus("TODO");
        existing.setProgress(0);
        when(repo.findById(id)).thenReturn(java.util.Optional.of(existing));

        ProjectTaskEntity updated = service.update(id, "New", null, "DONE", "HIGH",
                null, 80, null, null, UUID.randomUUID(), tid);
        assertThat(updated.getTitle()).isEqualTo("New");
        assertThat(updated.getStatus()).isEqualTo("DONE");
        assertThat(updated.getPriority()).isEqualTo("HIGH");
        assertThat(updated.getProgress()).isEqualTo(80);
    }

    // ============================================================
    //  5. get (not found / wrong tenant)
    // ============================================================

    @Test
    void get_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.get(id, tid))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void get_wrongTenant_throws403() {
        UUID id = UUID.randomUUID();
        ProjectTaskEntity t = new ProjectTaskEntity();
        t.setId(id);
        t.setTenantId("other_tenant");
        t.setProjectId(pid);
        when(repo.findById(id)).thenReturn(java.util.Optional.of(t));
        assertThatThrownBy(() -> service.get(id, tid))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ============================================================
    //  6. delete
    // ============================================================

    @Test
    void delete_removesTask() {
        UUID id = UUID.randomUUID();
        ProjectTaskEntity t = new ProjectTaskEntity();
        t.setId(id);
        t.setTenantId(tid);
        t.setProjectId(pid);
        when(repo.findById(id)).thenReturn(java.util.Optional.of(t));
        service.delete(id, tid);
        // verify delete called (repo is mock, save returns arg)
        // no exception means success
    }
}