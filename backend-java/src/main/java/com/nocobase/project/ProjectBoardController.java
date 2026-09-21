package com.nocobase.project;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.event.RecordChangeEvent;
import com.nocobase.project.CardChecklistItemEntity;
import com.nocobase.project.CardChecklistItemRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trello 式看板 API — 列/卡片移动/清单/标签/成员端点。
 */
@RestController
@RequestMapping("/api/project-boards")
public class ProjectBoardController {

    private final ProjectService projectService;
    private final CardMoveService cardMoveService;
    private final BoardListRepository boardListRepository;
    private final CardChecklistRepository checklistRepository;
    private final CardChecklistItemRepository checklistItemRepository;
    private final CardLabelRepository labelRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ProjectBoardController(ProjectService projectService, CardMoveService cardMoveService,
                                  BoardListRepository boardListRepository,
                                  CardChecklistRepository checklistRepository,
                                  CardChecklistItemRepository checklistItemRepository,
                                  CardLabelRepository labelRepository,
                                  ApplicationEventPublisher eventPublisher) {
        this.projectService = projectService;
        this.cardMoveService = cardMoveService;
        this.boardListRepository = boardListRepository;
        this.checklistRepository = checklistRepository;
        this.checklistItemRepository = checklistItemRepository;
        this.labelRepository = labelRepository;
        this.eventPublisher = eventPublisher;
    }

    // ============================================================
    //  Board List (列) CRUD
    // ============================================================

    /** 创建列：{projectId, title, type, sortOrder, wipLimit} */
    @PostMapping("/lists")
    public ResponseEntity<Map<String, Object>> createList(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String projectIdStr = (String) body.get("projectId");
        if (projectIdStr == null || projectIdStr.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "projectId 必填");
        }
        BoardListEntity list = new BoardListEntity();
        list.setProjectId(UUID.fromString(projectIdStr));
        list.setTitle((String) body.get("title"));
        list.setType((String) body.get("type"));
        list.setSortOrder(body.get("sortOrder") instanceof Number n ? n.intValue() : 0);
        list.setWipLimit(body.get("wipLimit") instanceof Number n ? n.intValue() : null);
        list.setTenantId(user.tenantId());
        UUID listId = UUID.randomUUID();
        list.setId(listId);
        list.setCreatedAt(Instant.now());
        list.setUpdatedAt(Instant.now());
        BoardListEntity saved = boardListRepository.save(list);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", Map.of("id", saved.getId().toString())));
    }

    /** 项目列列表 */
    @GetMapping("/{projectId}/lists")
    public Map<String, Object> listBoardLists(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<BoardListEntity> lists = boardListRepository.findByTenantIdAndProjectIdOrderBySortOrderAsc(user.tenantId(), projectId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (BoardListEntity l : lists) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", l.getId().toString());
            m.put("title", l.getTitle());
            m.put("type", l.getType());
            m.put("sortOrder", l.getSortOrder());
            m.put("wipLimit", l.getWipLimit());
            data.add(m);
        }
        return Map.of("code", 0, "message", "success", "data", data, "total", data.size());
    }

    /** 更新列 */
    @PutMapping("/lists/{listId}")
    public Map<String, Object> updateBoardList(
            @PathVariable UUID listId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        BoardListEntity list = boardListRepository.findById(listId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "List not found"));
        if (body.get("title") != null) list.setTitle((String) body.get("title"));
        if (body.get("type") != null) list.setType((String) body.get("type"));
        if (body.get("sortOrder") instanceof Number n) list.setSortOrder(n.intValue());
        if (body.get("wipLimit") instanceof Number n) list.setWipLimit(n.intValue());
        list.setUpdatedAt(Instant.now());
        boardListRepository.save(list);
        return Map.of("code", 0, "message", "updated", "data", Map.of("id", list.getId().toString()));
    }

    /** 删除列 */
    @DeleteMapping("/lists/{listId}")
    public Map<String, Object> deleteBoardList(
            @PathVariable UUID listId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        boardListRepository.findById(listId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "List not found"));
        boardListRepository.deleteById(listId);
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", listId.toString()));
    }

    // ============================================================
    //  Card Move (卡片移动)
    // ============================================================

    /** 移动卡片：{taskId, toListId, toIndex} */
    @PostMapping("/cards/move")
    public Map<String, Object> moveCard(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String taskIdStr = (String) body.get("taskId");
        String toListIdStr = (String) body.get("toListId");
        Integer toIndex = body.get("toIndex") instanceof Number n ? n.intValue() : 0;
        if (taskIdStr == null || toListIdStr == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "taskId 和 toListId 必填");
        }
        cardMoveService.moveCard(user.tenantId(),
                UUID.fromString(taskIdStr),
                UUID.fromString(toListIdStr),
                toIndex);
        return Map.of("code", 0, "message", "moved", "data", Map.of());
    }

    // ============================================================
    //  Checklist (清单) CRUD
    // ============================================================

    /** 创建清单：{taskId, title, sortOrder} */
    @PostMapping("/checklists")
    public ResponseEntity<Map<String, Object>> createChecklist(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String taskIdStr = (String) body.get("taskId");
        if (taskIdStr == null || taskIdStr.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "taskId 必填");
        }
        CardChecklistEntity checklist = new CardChecklistEntity();
        checklist.setTaskId(UUID.fromString(taskIdStr));
        checklist.setTitle((String) body.get("title"));
        checklist.setSortOrder(body.get("sortOrder") instanceof Number n ? n.intValue() : 0);
        checklist.setTenantId(user.tenantId());
        UUID checklistId = UUID.randomUUID();
        checklist.setId(checklistId);
        checklist.setCreatedAt(Instant.now());
        CardChecklistEntity saved = checklistRepository.save(checklist);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", Map.of("id", saved.getId().toString())));
    }

    /** 任务清单列表 */
    @GetMapping("/checklists/by-task/{taskId}")
    public Map<String, Object> listChecklistsByTask(
            @PathVariable UUID taskId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<CardChecklistEntity> items = checklistRepository.findByTenantIdAndTaskIdOrderBySortOrderAsc(user.tenantId(), taskId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (CardChecklistEntity c : items) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId().toString());
            m.put("title", c.getTitle());
            m.put("sortOrder", c.getSortOrder());
            data.add(m);
        }
        return Map.of("code", 0, "message", "success", "data", data, "total", data.size());
    }

    /** 更新清单 */
    @PutMapping("/checklists/{checklistId}")
    public Map<String, Object> updateChecklist(
            @PathVariable UUID checklistId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CardChecklistEntity c = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist not found"));
        if (body.get("title") != null) c.setTitle((String) body.get("title"));
        checklistRepository.save(c);
        return Map.of("code", 0, "message", "updated", "data", Map.of("id", c.getId().toString()));
    }

    /** 删除清单 */
    @DeleteMapping("/checklists/{checklistId}")
    public Map<String, Object> deleteChecklist(
            @PathVariable UUID checklistId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        checklistRepository.findById(checklistId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Checklist not found"));
        checklistRepository.deleteById(checklistId);
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", checklistId.toString()));
    }

    // ============================================================
    //  Checklist Item (清单项) CRUD
    // ============================================================

    /** 创建清单项：{checklistId, taskId, title, sortOrder} */
    @PostMapping("/checklist-items")
    public ResponseEntity<Map<String, Object>> createChecklistItem(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String checklistIdStr = (String) body.get("checklistId");
        String taskIdStr = (String) body.get("taskId");
        if (checklistIdStr == null || taskIdStr == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "checklistId 和 taskId 必填");
        }
        CardChecklistItemEntity item = new CardChecklistItemEntity();
        item.setChecklistId(UUID.fromString(checklistIdStr));
        item.setTaskId(UUID.fromString(taskIdStr));
        item.setTitle((String) body.get("title"));
        item.setDone(false);
        item.setSortOrder(body.get("sortOrder") instanceof Number n ? n.intValue() : 0);
        item.setTenantId(user.tenantId());
        UUID itemId = UUID.randomUUID();
        item.setId(itemId);
        item.setCreatedAt(Instant.now());
        CardChecklistItemEntity savedItem = checklistItemRepository.save(item);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", Map.of("id", savedItem.getId().toString())));
    }

    /** 清单项列表 */
    @GetMapping("/checklist-items/by-checklist/{checklistId}")
    public Map<String, Object> listChecklistItems(
            @PathVariable UUID checklistId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<CardChecklistItemEntity> items = checklistItemRepository.findByTenantIdAndChecklistIdOrderBySortOrderAsc(user.tenantId(), checklistId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (CardChecklistItemEntity i : items) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", i.getId().toString());
            m.put("title", i.getTitle());
            m.put("done", i.getDone());
            m.put("sortOrder", i.getSortOrder());
            data.add(m);
        }
        return Map.of("code", 0, "message", "success", "data", data, "total", data.size());
    }

    /** 更新清单项（标记完成） */
    @PutMapping("/checklist-items/{itemId}")
    public Map<String, Object> updateChecklistItem(
            @PathVariable UUID itemId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CardChecklistItemEntity item = checklistItemRepository.findById(itemId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        Boolean done = body.get("done") instanceof Boolean b ? b : false;
        item.setDone(done);
        checklistItemRepository.save(item);
        return Map.of("code", 0, "message", "updated", "data", Map.of("id", item.getId().toString(), "done", done));
    }

    /** 删除清单项 */
    @DeleteMapping("/checklist-items/{itemId}")
    public Map<String, Object> deleteChecklistItem(
            @PathVariable UUID itemId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        checklistItemRepository.findById(itemId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        checklistItemRepository.deleteById(itemId);
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", itemId.toString()));
    }

    // ============================================================
    //  Label (标签) CRUD
    // ============================================================

    /** 创建标签：{projectId, name, color} */
    @PostMapping("/labels")
    public ResponseEntity<Map<String, Object>> createLabel(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String projectIdStr = (String) body.get("projectId");
        if (projectIdStr == null || projectIdStr.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "projectId 必填");
        }
        CardLabelEntity label = new CardLabelEntity();
        label.setProjectId(UUID.fromString(projectIdStr));
        label.setName((String) body.get("name"));
        label.setColor((String) body.get("color"));
        label.setTenantId(user.tenantId());
        label.setCreatedAt(Instant.now());
        UUID labelId = UUID.randomUUID();
        label.setId(labelId);
        CardLabelEntity saved = labelRepository.save(label);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", Map.of("id", saved.getId().toString())));
    }

    /** 项目标签列表 */
    @GetMapping("/labels/by-project/{projectId}")
    public Map<String, Object> listLabelsByProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<CardLabelEntity> labels = labelRepository.findByTenantIdAndProjectId(user.tenantId(), projectId);
        List<Map<String, Object>> data = new ArrayList<>();
        for (CardLabelEntity l : labels) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", l.getId().toString());
            m.put("name", l.getName());
            m.put("color", l.getColor());
            data.add(m);
        }
        return Map.of("code", 0, "message", "success",
                "data", data,
                "total", data.size());
    }

    /** 更新标签 */
    @PutMapping("/labels/{labelId}")
    public Map<String, Object> updateLabel(
            @PathVariable UUID labelId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        CardLabelEntity label = labelRepository.findById(labelId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found"));
        if (body.get("name") != null) label.setName((String) body.get("name"));
        if (body.get("color") != null) label.setColor((String) body.get("color"));
        labelRepository.save(label);
        return Map.of("code", 0, "message", "updated", "data", Map.of("id", label.getId().toString()));
    }

    /** 删除标签 */
    @DeleteMapping("/labels/{labelId}")
    public Map<String, Object> deleteLabel(
            @PathVariable UUID labelId,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        labelRepository.findById(labelId).orElseThrow(
                () -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Label not found"));
        labelRepository.deleteById(labelId);
        return Map.of("code", 0, "message", "deleted", "data", Map.of("id", labelId.toString()));
    }

    // ============================================================
    //  Member (成员) — 复用 ProjectTask assignee
    // ============================================================

    /** 分配成员到任务：{taskId, assigneeId} */
    @PostMapping("/members/assign")
    public Map<String, Object> assignMember(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String taskIdStr = (String) body.get("taskId");
        String assigneeIdStr = (String) body.get("assigneeId");
        if (taskIdStr == null || assigneeIdStr == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "taskId 和 assigneeId 必填");
        }
        // 复用 updateTask API
        projectService.update(
                UUID.fromString(taskIdStr),
                null, null, null, null,
                UUID.fromString(assigneeIdStr),
                null, null, null,
                user.userId(), user.tenantId()
        );
        return Map.of("code", 0, "message", "assigned", "data", Map.of());
    }

    // ============================================================
    //  Task (任务) CRUD — 发布 RecordChangeEvent 供搜索索引同步
    // ============================================================

    /** 创建任务：{projectId, title, description, parentId, assigneeId, status, priority, startDate, endDate} */
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
        UUID projectId = UUID.fromString(projectIdStr);
        UUID parentId = body.get("parentId") != null
                ? UUID.fromString(String.valueOf(body.get("parentId"))) : null;
        UUID assigneeId = body.get("assigneeId") != null
                ? UUID.fromString(String.valueOf(body.get("assigneeId"))) : null;

        ProjectTaskEntity task = projectService.create(
                projectId,
                str(body.get("title")),
                str(body.get("description")),
                parentId, assigneeId,
                str(body.get("status")),
                str(body.get("priority")),
                parseInstant(body.get("startDate")),
                parseInstant(body.get("endDate")),
                user.userId(), user.tenantId()
        );

        publishTaskEvent(RecordChangeEvent.ChangeType.CREATE, task, user);
        return ResponseEntity.status(HttpStatus.CREATED).body(
                Map.of("code", 0, "message", "success", "data", Map.of("id", task.getId().toString())));
    }

    /** 更新任务：{id, title?, description?, status?, priority?, assigneeId?, startDate?, endDate?} */
    @PutMapping("/tasks/{id}")
    public Map<String, Object> updateTask(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        ProjectTaskEntity task = projectService.update(
                id,
                str(body.get("title")),
                str(body.get("description")),
                str(body.get("status")),
                str(body.get("priority")),
                body.get("assigneeId") != null ? UUID.fromString(String.valueOf(body.get("assigneeId"))) : null,
                body.get("progress") instanceof Number n ? n.intValue() : null,
                parseInstant(body.get("startDate")),
                parseInstant(body.get("endDate")),
                user.userId(), user.tenantId()
        );
        publishTaskEvent(RecordChangeEvent.ChangeType.UPDATE, task, user);
        return Map.of("code", 0, "message", "success", "data", Map.of("id", task.getId().toString()));
    }

    /** 删除任务 */
    @DeleteMapping("/tasks/{id}")
    public Map<String, Object> deleteTask(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        projectService.delete(id, user.tenantId());
        publishTaskEvent(RecordChangeEvent.ChangeType.DELETE, null, user);
        return Map.of("code", 0, "message", "success", "data", Map.of("id", id.toString()));
    }

    /** 发布任务变更事件（供搜索索引同步）。 */
    private void publishTaskEvent(RecordChangeEvent.ChangeType type,
                                   ProjectTaskEntity task,
                                   AuthenticatedUser user) {
        String recordId = task != null ? task.getId().toString() : null;
        Map<String, Object> data = null;
        if (task != null && (type == RecordChangeEvent.ChangeType.CREATE || type == RecordChangeEvent.ChangeType.UPDATE)) {
            data = new LinkedHashMap<>();
            data.put("title", task.getTitle());
            data.put("description", task.getDescription());
            data.put("status", task.getStatus());
            data.put("projectId", task.getProjectId());
        }
        eventPublisher.publishEvent(new RecordChangeEvent(type, "project_task", recordId, data,
                user.tenantId(), user.userId()));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return null;
        try {
            return Instant.parse(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}