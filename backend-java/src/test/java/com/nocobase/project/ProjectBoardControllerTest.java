package com.nocobase.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProjectBoardController 端点测试（PHASE108 冲 85%）。
 *
 * <p>覆盖列/卡片移动/清单/清单项/标签/成员/任务 共 18 个端点。
 */
class ProjectBoardControllerTest {

    private ProjectService projectService;
    private CardMoveService cardMoveService;
    private BoardListRepository boardListRepository;
    private CardChecklistRepository checklistRepository;
    private CardChecklistItemRepository checklistItemRepository;
    private CardLabelRepository labelRepository;
    private ApplicationEventPublisher eventPublisher;
    private ProjectBoardController controller;

    private String tenant;
    private UUID user;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        cardMoveService = mock(CardMoveService.class);
        boardListRepository = mock(BoardListRepository.class);
        checklistRepository = mock(CardChecklistRepository.class);
        checklistItemRepository = mock(CardChecklistItemRepository.class);
        labelRepository = mock(CardLabelRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        controller = new ProjectBoardController(projectService, cardMoveService, boardListRepository,
                checklistRepository, checklistItemRepository, labelRepository, eventPublisher);

        tenant = "tenant1";
        user = UUID.randomUUID();
    }

    private AuthenticatedUser u() {
        return new AuthenticatedUser(user, "alice", tenant);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> r) {
        return (Map<String, Object>) r.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map<String, Object>> r) {
        return (Map<String, Object>) r.getBody().get("data");
    }

    // ---- 列 CRUD ----

    @Test
    void createList_success() {
        UUID projectId = UUID.randomUUID();
        when(boardListRepository.save(any(BoardListEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> r = controller.createList(
                Map.of("projectId", projectId.toString(), "title", "Todo", "sortOrder", 2), u());

        assertEquals(201, r.getStatusCode().value());
        Object id = data(r).get("id");
        org.junit.jupiter.api.Assertions.assertTrue(id instanceof String && !((String) id).isBlank());
    }

    @Test
    void createList_missingProjectId_throws400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createList(Map.of("title", "Todo"), u()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void listBoardLists_returnsData() {
        UUID projectId = UUID.randomUUID();
        BoardListEntity list = new BoardListEntity();
        list.setId(UUID.randomUUID());
        list.setTitle("Todo");
        list.setSortOrder(1);
        when(boardListRepository.findByTenantIdAndProjectIdOrderBySortOrderAsc(tenant, projectId))
                .thenReturn(List.of(list));

        Map<String, Object> r = controller.listBoardLists(projectId, u());

        assertEquals(1, r.get("total"));
    }

    @Test
    void updateBoardList_success() {
        UUID listId = UUID.randomUUID();
        BoardListEntity list = new BoardListEntity();
        list.setId(listId);
        list.setTenantId(tenant);
        when(boardListRepository.findById(listId)).thenReturn(Optional.of(list));
        when(boardListRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> r = controller.updateBoardList(listId,
                Map.of("title", "Updated"), u());

        assertEquals("updated", r.get("message"));
    }

    @Test
    void updateBoardList_tenantMismatch_throws403() {
        UUID listId = UUID.randomUUID();
        BoardListEntity list = new BoardListEntity();
        list.setId(listId);
        list.setTenantId("other-tenant");
        when(boardListRepository.findById(listId)).thenReturn(Optional.of(list));

        assertThrows(ResponseStatusException.class,
                () -> controller.updateBoardList(listId, Map.of("title", "X"), u()));
    }

    @Test
    void deleteBoardList_success() {
        UUID listId = UUID.randomUUID();
        when(boardListRepository.findById(listId)).thenReturn(Optional.of(new BoardListEntity()));

        Map<String, Object> r = controller.deleteBoardList(listId, u());

        assertEquals("deleted", r.get("message"));
        verify(boardListRepository).deleteById(listId);
    }

    // ---- 卡片移动 ----

    @Test
    void moveCard_success() {
        UUID taskId = UUID.randomUUID();
        UUID toListId = UUID.randomUUID();

        Map<String, Object> r = controller.moveCard(
                Map.of("taskId", taskId.toString(), "toListId", toListId.toString(), "toIndex", 3), u());

        assertEquals("moved", r.get("message"));
        verify(cardMoveService).moveCard(eq(tenant), eq(taskId), eq(toListId), eq(3));    }

    @Test
    void moveCard_missingFields_throws400() {
        assertThrows(ResponseStatusException.class,
                () -> controller.moveCard(Map.of("taskId", "x"), u()));
    }

    // ---- 清单 CRUD ----

    @Test
    void createChecklist_success() {
        UUID taskId = UUID.randomUUID();
        when(checklistRepository.save(any(CardChecklistEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> r = controller.createChecklist(
                Map.of("taskId", taskId.toString(), "title", "checklist"), u());

        assertEquals(201, r.getStatusCode().value());
    }

    @Test
    void listChecklistsByTask_returnsData() {
        UUID taskId = UUID.randomUUID();
        CardChecklistEntity c = new CardChecklistEntity();
        c.setId(UUID.randomUUID());
        c.setTitle("c1");
        when(checklistRepository.findByTenantIdAndTaskIdOrderBySortOrderAsc(tenant, taskId))
                .thenReturn(List.of(c));

        Map<String, Object> r = controller.listChecklistsByTask(taskId, u());

        assertEquals(1, r.get("total"));
    }

    @Test
    void updateChecklist_success() {
        UUID id = UUID.randomUUID();
        CardChecklistEntity c = new CardChecklistEntity();
        c.setId(id);
        c.setTenantId(tenant);
        when(checklistRepository.findById(id)).thenReturn(Optional.of(c));
        when(checklistRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> r = controller.updateChecklist(id, Map.of("title", "new"), u());

        assertEquals("updated", r.get("message"));
    }

    @Test
    void deleteChecklist_success() {
        UUID id = UUID.randomUUID();
        CardChecklistEntity c = new CardChecklistEntity();
        c.setId(id);
        c.setTenantId(tenant);
        when(checklistRepository.findById(id)).thenReturn(Optional.of(c));

        Map<String, Object> r = controller.deleteChecklist(id, u());

        assertEquals("deleted", r.get("message"));
        verify(checklistRepository).deleteById(id);
    }

    // ---- 清单项 CRUD ----

    @Test
    void createChecklistItem_success() {
        UUID checklistId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(checklistItemRepository.save(any(CardChecklistItemEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> r = controller.createChecklistItem(
                Map.of("checklistId", checklistId.toString(), "taskId", taskId.toString(), "title", "item"), u());

        assertEquals(201, r.getStatusCode().value());
    }

    @Test
    void listChecklistItems_returnsData() {
        UUID checklistId = UUID.randomUUID();
        CardChecklistItemEntity item = new CardChecklistItemEntity();
        item.setId(UUID.randomUUID());
        item.setTitle("item1");
        item.setDone(false);
        when(checklistItemRepository.findByTenantIdAndChecklistIdOrderBySortOrderAsc(tenant, checklistId))
                .thenReturn(List.of(item));

        Map<String, Object> r = controller.listChecklistItems(checklistId, u());

        assertEquals(1, r.get("total"));
    }

    @Test
    void updateChecklistItem_marksDone() {
        UUID id = UUID.randomUUID();
        CardChecklistItemEntity item = new CardChecklistItemEntity();
        item.setId(id);
        item.setTenantId(tenant);
        when(checklistItemRepository.findById(id)).thenReturn(Optional.of(item));
        when(checklistItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> r = controller.updateChecklistItem(id, Map.of("done", true), u());

        assertEquals(true, data(r).get("done"));
    }

    @Test
    void deleteChecklistItem_success() {
        UUID id = UUID.randomUUID();
        CardChecklistItemEntity item = new CardChecklistItemEntity();
        item.setId(id);
        item.setTenantId(tenant);
        when(checklistItemRepository.findById(id)).thenReturn(Optional.of(item));

        Map<String, Object> r = controller.deleteChecklistItem(id, u());

        assertEquals("deleted", r.get("message"));
        verify(checklistItemRepository).deleteById(id);
    }

    // ---- 标签 CRUD ----

    @Test
    void createLabel_success() {
        UUID projectId = UUID.randomUUID();
        when(labelRepository.save(any(CardLabelEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> r = controller.createLabel(
                Map.of("projectId", projectId.toString(), "name", "bug", "color", "red"), u());

        assertEquals(201, r.getStatusCode().value());
    }

    @Test
    void listLabelsByProject_returnsData() {
        UUID projectId = UUID.randomUUID();
        CardLabelEntity label = new CardLabelEntity();
        label.setId(UUID.randomUUID());
        label.setName("bug");
        when(labelRepository.findByTenantIdAndProjectId(tenant, projectId)).thenReturn(List.of(label));

        Map<String, Object> r = controller.listLabelsByProject(projectId, u());

        assertEquals(1, r.get("total"));
    }

    @Test
    void updateLabel_success() {
        UUID id = UUID.randomUUID();
        CardLabelEntity label = new CardLabelEntity();
        label.setId(id);
        label.setTenantId(tenant);
        when(labelRepository.findById(id)).thenReturn(Optional.of(label));
        when(labelRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> r = controller.updateLabel(id, Map.of("name", "new"), u());

        assertEquals("updated", r.get("message"));
    }

    @Test
    void deleteLabel_success() {
        UUID id = UUID.randomUUID();
        when(labelRepository.findById(id)).thenReturn(Optional.of(new CardLabelEntity()));

        Map<String, Object> r = controller.deleteLabel(id, u());

        assertEquals("deleted", r.get("message"));
        verify(labelRepository).deleteById(id);
    }

    // ---- 成员分配 ----

    @Test
    void assignMember_delegatesToProjectService() {
        UUID taskId = UUID.randomUUID();
        UUID assigneeId = UUID.randomUUID();
        when(projectService.update(ArgumentMatchers.any(UUID.class),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                eq(assigneeId), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                eq(user), eq(tenant)))
                .thenReturn(new ProjectTaskEntity());

        Map<String, Object> r = controller.assignMember(
                Map.of("taskId", taskId.toString(), "assigneeId", assigneeId.toString()), u());

        assertEquals("assigned", r.get("message"));
        verify(cardMoveService, org.mockito.Mockito.never()).moveCard(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyInt());
    }

    @Test
    void assignMember_missingFields_throws400() {
        assertThrows(ResponseStatusException.class,
                () -> controller.assignMember(Map.of("taskId", "x"), u()));
    }

    // ---- 任务 CRUD ----

    @Test
    void createTask_success() {
        UUID projectId = UUID.randomUUID();
        ProjectTaskEntity task = new ProjectTaskEntity();
        task.setId(UUID.randomUUID());
        when(projectService.create(
                eq(projectId),
                ArgumentMatchers.<String>any(),
                ArgumentMatchers.<String>any(),
                ArgumentMatchers.<UUID>any(),
                ArgumentMatchers.<UUID>any(),
                ArgumentMatchers.<String>any(),
                ArgumentMatchers.<String>any(),
                ArgumentMatchers.<java.time.Instant>any(),
                ArgumentMatchers.<java.time.Instant>any(),
                eq(user), eq(tenant)))
                .thenReturn(task);

        ResponseEntity<Map<String, Object>> r = controller.createTask(
                Map.of("projectId", projectId.toString(), "title", "Task"), u());

        assertEquals(201, r.getStatusCode().value());
        verify(eventPublisher).publishEvent(ArgumentMatchers.any(Object.class));
    }

    @Test
    void updateTask_success() {
        UUID id = UUID.randomUUID();
        ProjectTaskEntity task = new ProjectTaskEntity();
        task.setId(id);
        when(projectService.update(eq(id), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                eq(user), eq(tenant)))
                .thenReturn(task);

        Map<String, Object> r = controller.updateTask(id, Map.of("status", "done"), u());

        assertEquals("success", r.get("message"));
    }

    @Test
    void deleteTask_success() {
        UUID id = UUID.randomUUID();

        Map<String, Object> r = controller.deleteTask(id, u());

        assertEquals("success", r.get("message"));
        verify(projectService).delete(eq(id), eq(tenant));
        verify(eventPublisher).publishEvent(ArgumentMatchers.any(Object.class));
    }
}
