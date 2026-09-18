package com.nocobase.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目任务服务 — 看板(Trello 对标)+ 甘特图。
 *
 * <p>Week 44 接真:此前的 listByProject / listGantt 直接返回空列表(占位),
 * 现改为经 {@link ProjectTaskRepository} 查询真实数据。
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectTaskRepository taskRepository;

    public ProjectService(ProjectTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /** 创建任务。 */
    @Transactional
    public ProjectTaskEntity create(UUID projectId, String title, String description,
                                    UUID parentId, UUID assigneeId,
                                    String status, String priority,
                                    Instant startDate, Instant endDate,
                                    UUID createdBy, String tenantId) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务标题必填");
        }
        ProjectTaskEntity task = new ProjectTaskEntity();
        task.setId(UUID.randomUUID());
        task.setTenantId(tenantId);
        task.setProjectId(projectId);
        task.setParentId(parentId);
        task.setTitle(title);
        task.setDescription(description);
        task.setAssigneeId(assigneeId);
        task.setStatus(status != null ? status : "TODO");
        task.setPriority(priority != null ? priority : "MEDIUM");
        task.setStartDate(startDate);
        task.setEndDate(endDate);
        task.setProgress(0);
        task.setSortOrder(0);
        task.setCreatedBy(createdBy);
        task.setUpdatedBy(createdBy);
        task.setCreatedAt(Instant.now());
        task.setUpdatedAt(Instant.now());
        return taskRepository.save(task);
    }

    /** 项目下全部任务。 */
    public List<ProjectTaskEntity> listByProject(UUID projectId, String tenantId) {
        return taskRepository
                .findByTenantIdAndProjectIdOrderBySortOrderAscCreatedAtAsc(tenantId, projectId);
    }

    /** 按状态取任务(看板单列)。 */
    public List<ProjectTaskEntity> listByStatus(UUID projectId, String status, String tenantId) {
        return taskRepository
                .findByTenantIdAndProjectIdAndStatusOrderBySortOrderAsc(tenantId, projectId, status);
    }

    /** 我的任务(按截止日期升序)。 */
    public List<ProjectTaskEntity> listMyTasks(UUID assigneeId, String tenantId) {
        return taskRepository.findByTenantIdAndAssigneeIdOrderByEndDateAsc(tenantId, assigneeId);
    }

    /**
     * 甘特图数据:带父子层级与日期区间。
     *
     * @return 顶层任务列表,每项含 {@code children}(递归子任务)
     */
    public List<Map<String, Object>> listGantt(UUID projectId, String tenantId) {
        List<ProjectTaskEntity> all = listByProject(projectId, tenantId);
        List<ProjectTaskEntity> roots = all.stream()
                .filter(t -> t.getParentId() == null)
                .toList();
        log.info("[Project] gantt: project={}, tasks={}, roots={}",
                projectId, all.size(), roots.size());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectTaskEntity root : roots) {
            out.add(toGanttNode(root, all));
        }
        return out;
    }

    /** 更新任务(状态/进度/派工/日期等)。 */
    @Transactional
    public ProjectTaskEntity update(UUID id, String title, String description,
                                    String status, String priority,
                                    UUID assigneeId, Integer progress,
                                    Instant startDate, Instant endDate,
                                    UUID updatedBy, String tenantId) {
        ProjectTaskEntity task = get(id, tenantId);
        if (title != null) task.setTitle(title);
        if (description != null) task.setDescription(description);
        if (status != null) task.setStatus(status);
        if (priority != null) task.setPriority(priority);
        if (assigneeId != null) task.setAssigneeId(assigneeId);
        if (progress != null) task.setProgress(clamp(progress));
        if (startDate != null) task.setStartDate(startDate);
        if (endDate != null) task.setEndDate(endDate);
        task.setUpdatedBy(updatedBy);
        task.setUpdatedAt(Instant.now());
        return taskRepository.save(task);
    }

    /** 删除任务。 */
    @Transactional
    public void delete(UUID id, String tenantId) {
        ProjectTaskEntity task = get(id, tenantId);
        taskRepository.delete(task);
    }

    /** 取单条(带租户校验)。 */
    public ProjectTaskEntity get(UUID id, String tenantId) {
        ProjectTaskEntity task = taskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + id));
        if (!tenantId.equals(task.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该任务");
        }
        return task;
    }

    // ============================================================
    //  视图模型
    // ============================================================

    /** 甘特节点:自身字段 + children(递归)。 */
    private Map<String, Object> toGanttNode(ProjectTaskEntity task, List<ProjectTaskEntity> all) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", task.getId().toString());
        node.put("title", task.getTitle());
        node.put("status", task.getStatus());
        node.put("priority", task.getPriority());
        node.put("progress", task.getProgress());
        node.put("startDate", task.getStartDate() == null ? null : task.getStartDate().toString());
        node.put("endDate", task.getEndDate() == null ? null : task.getEndDate().toString());
        node.put("assigneeId", task.getAssigneeId() == null ? null : task.getAssigneeId().toString());
        List<Map<String, Object>> children = all.stream()
                .filter(t -> task.getId().equals(t.getParentId()))
                .map(c -> toGanttNode(c, all))
                .toList();
        node.put("children", children);
        return node;
    }

    private static int clamp(Integer p) {
        if (p == null) return 0;
        return Math.max(0, Math.min(100, p));
    }
}
