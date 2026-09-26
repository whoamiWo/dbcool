package com.nocobase.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * ProjectBoardController 单元测试(PHASE 55 Stage 5 对标补齐 — Trello 卡片能力)。
 *
 * <p>覆盖 Trello 式看板端点:列 CRUD、卡片移动、清单、标签;
 * 重点验证参数校验拒绝(400)与资源不存在(404),避免静默放行。
 */
class ProjectBoardControllerTest {

    private static final String TENANT = "tenant_default";
    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "admin", TENANT);

    private ProjectService projectService;
    private CardMoveService cardMoveService;
    private BoardListRepository boardListRepository;
    private CardChecklistRepository checklistRepository;
    private CardChecklistItemRepository checklistItemRepository;
    private CardLabelRepository labelRepository;
    private ApplicationEventPublisher eventPublisher;
    private ProjectBoardController controller;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        cardMoveService = mock(CardMoveService.class);
        boardListRepository = mock(BoardListRepository.class);
        checklistRepository = mock(CardChecklistRepository.class);
        checklistItemRepository = mock(CardChecklistItemRepository.class);
        labelRepository = mock(CardLabelRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(boardListRepository.save(any(BoardListEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(checklistRepository.save(any(CardChecklistEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(labelRepository.save(any(CardLabelEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        controller = new ProjectBoardController(projectService, cardMoveService,
                boardListRepository, checklistRepository, checklistItemRepository,
                labelRepository, eventPublisher);
    }

    // ==================== 列(Board List) ====================

    @Test
    void createList_missingProjectId_throws400() {
        assertThatThrownBy(() -> controller.createList(Map.of("title", "待办"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createList_valid_returns201WithId() {
        UUID projectId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> resp = controller.createList(
                Map.of("projectId", projectId.toString(), "title", "待办", "sortOrder", 1), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data).containsKey("id");
    }

    @Test
    void listBoardLists_returnsDataAndTotal() {
        UUID projectId = UUID.randomUUID();
        BoardListEntity l = new BoardListEntity();
        l.setId(UUID.randomUUID());
        l.setTitle("进行中");
        l.setType("NORMAL");
        when(boardListRepository.findByTenantIdAndProjectIdOrderBySortOrderAsc(TENANT, projectId))
                .thenReturn(List.of(l));

        Map<String, Object> resp = controller.listBoardLists(projectId, USER);

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("total")).isEqualTo(1);
    }

    @Test
    void updateBoardList_notFound_throws404() {
        UUID listId = UUID.randomUUID();
        when(boardListRepository.findById(listId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.updateBoardList(listId, Map.of("title", "x"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateBoardList_valid_updatesTitle() {
        UUID listId = UUID.randomUUID();
        BoardListEntity l = new BoardListEntity();
        l.setId(listId);
        l.setTitle("旧");
        when(boardListRepository.findById(listId)).thenReturn(Optional.of(l));

        Map<String, Object> resp = controller.updateBoardList(listId, Map.of("title", "新"), USER);

        assertThat(l.getTitle()).isEqualTo("新");
        assertThat(resp.get("message")).isEqualTo("updated");
    }

    @Test
    void deleteBoardList_notFound_throws404() {
        UUID listId = UUID.randomUUID();
        when(boardListRepository.findById(listId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.deleteBoardList(listId, USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== 卡片移动 ====================

    @Test
    void moveCard_missingParams_throws400() {
        assertThatThrownBy(() -> controller.moveCard(Map.of("taskId", UUID.randomUUID().toString()), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void moveCard_valid_invokesCardMoveService() {
        UUID taskId = UUID.randomUUID();
        UUID toListId = UUID.randomUUID();
        Map<String, Object> resp = controller.moveCard(
                Map.of("taskId", taskId.toString(), "toListId", toListId.toString(), "toIndex", 2), USER);
        verify(cardMoveService).moveCard(eq(TENANT), eq(taskId), eq(toListId), anyInt());
        assertThat(resp.get("code")).isEqualTo(0);
    }

    // ==================== 清单(Checklist) ====================

    @Test
    void createChecklist_missingTaskId_throws400() {
        assertThatThrownBy(() -> controller.createChecklist(Map.of("title", "清单"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createChecklist_valid_returns201() {
        UUID taskId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> resp = controller.createChecklist(
                Map.of("taskId", taskId.toString(), "title", "验收项"), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listChecklistsByTask_returnsTotal() {
        UUID taskId = UUID.randomUUID();
        CardChecklistEntity c = new CardChecklistEntity();
        c.setId(UUID.randomUUID());
        c.setTitle("清单");
        when(checklistRepository.findByTenantIdAndTaskIdOrderBySortOrderAsc(TENANT, taskId))
                .thenReturn(List.of(c));

        Map<String, Object> resp = controller.listChecklistsByTask(taskId, USER);

        assertThat(resp.get("total")).isEqualTo(1);
    }

    @Test
    void updateChecklist_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(checklistRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.updateChecklist(id, Map.of("title", "x"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================== 标签(Label) ====================

    @Test
    void createLabel_missingProjectId_throws400() {
        assertThatThrownBy(() -> controller.createLabel(Map.of("name", "紧急"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createLabel_valid_returns201() {
        UUID projectId = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> resp = controller.createLabel(
                Map.of("projectId", projectId.toString(), "name", "紧急", "color", "#f00"), USER);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listLabelsByProject_returnsNameAndColor() {
        UUID projectId = UUID.randomUUID();
        CardLabelEntity label = new CardLabelEntity();
        label.setId(UUID.randomUUID());
        label.setName("紧急");
        label.setColor("#f00");
        when(labelRepository.findByTenantIdAndProjectId(TENANT, projectId))
                .thenReturn(List.of(label));

        Map<String, Object> resp = controller.listLabelsByProject(projectId, USER);

        assertThat(resp.get("total")).isEqualTo(1);
    }

    @Test
    void updateLabel_notFound_throws404() {
        UUID id = UUID.randomUUID();
        when(labelRepository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.updateLabel(id, Map.of("name", "x"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateLabel_valid_updatesName() {
        UUID id = UUID.randomUUID();
        CardLabelEntity label = new CardLabelEntity();
        label.setId(id);
        label.setName("旧");
        when(labelRepository.findById(id)).thenReturn(Optional.of(label));

        controller.updateLabel(id, Map.of("name", "新"), USER);

        assertThat(label.getName()).isEqualTo("新");
    }
}