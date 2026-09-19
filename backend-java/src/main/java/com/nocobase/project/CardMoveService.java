package com.nocobase.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 卡片拖拽服务 — Trello 对标 CardMoveService。
 *
 * <p>支持跨列拖拽（更新 status + sort_order）和列内重排。
 */
@Service
public class CardMoveService {

    private static final Logger log = LoggerFactory.getLogger(CardMoveService.class);

    private final ProjectTaskRepository taskRepository;
    private final BoardListRepository boardListRepository;

    public CardMoveService(ProjectTaskRepository taskRepository, BoardListRepository boardListRepository) {
        this.taskRepository = taskRepository;
        this.boardListRepository = boardListRepository;
    }

    /**
     * 拖拽卡片到目标列。
     *
     * @param tenantId 租户 ID
     * @param taskId   卡片 ID
     * @param toListId 目标列 ID（可为 null 表示保持原列）
     * @param toIndex 目标位置索引
     */
    @Transactional
    public void moveCard(String tenantId, UUID taskId, UUID toListId, int toIndex) {
        var task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

        // 获取目标列信息
        String newStatus = task.getStatus();
        if (toListId != null) {
            var list = boardListRepository.findById(toListId)
                    .orElseThrow(() -> new IllegalArgumentException("List not found: " + toListId));
            newStatus = list.getType();
        }

        // 获取目标列全部卡片
        var siblings = taskRepository.findByTenantIdAndProjectIdAndStatusOrderBySortOrderAsc(
                tenantId, task.getProjectId(), newStatus);

        // 移除当前任务并插入到目标位置
        siblings.removeIf(t -> t.getId().equals(taskId));
        if (toIndex < 0) toIndex = 0;
        if (toIndex > siblings.size()) toIndex = siblings.size();
        siblings.add(toIndex, task);

        // 重排 sort_order
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).setSortOrder(i);
        }
        task.setStatus(newStatus);
        task.setUpdatedAt(Instant.now());
        taskRepository.saveAll(siblings);
        log.info("Moved card {} to status={} at index={}", taskId, newStatus, toIndex);
    }
}