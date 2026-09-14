package com.nocobase.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.event.RecordChangeEvent;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工作流触发器匹配器(Week 41 D4a — 触发器真实化).
 *
 * <p>根据 RecordChangeEvent 与 WorkflowEntity.trigger_json 匹配,决定哪些工作流应被触发。
 *
 * <p>支持的 trigger 类型:
 * <ul>
 *   <li>manual — 仅手动触发(/api/workflows/{id}/trigger),不自动响应事件</li>
 *   <li>on_create — 集合创建记录时触发</li>
 *   <li>on_update — 集合更新记录时触发</li>
 *   <li>on_delete — 集合删除记录时触发</li>
 *   <li>schedule — 定时调度(由 WorkflowScheduler 触发,与本类无关)</li>
 * </ul>
 *
 * <p>trigger_json 示例:
 * <pre>
 *   { "type": "on_create" }
 *   { "type": "on_update", "filter": "status=pending" }  // 未来扩展
 * </pre>
 */
@Component
public class WorkflowTriggerMatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTriggerMatcher.class);

    private final WorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;

    public WorkflowTriggerMatcher(WorkflowRepository workflowRepository, ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据事件找匹配的工作流。
     *
     * <p>匹配规则:trigger_json.type 对应到事件类型 + collection 一致 + 工作流 enabled。
     */
    public List<WorkflowEntity> findMatching(RecordChangeEvent event) {
        String expectedType = mapEventToTriggerType(event.getChangeType());
        if (expectedType == null) return List.of(); // 无对应类型,不匹配任何工作流

        // 当前 tenant 下 + collection 一致 + enabled + trigger_json.type 匹配
        return workflowRepository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(
                event.getCollectionName(), event.getTenantId()
        ).stream()
                .filter(WorkflowEntity::isEnabled)
                .filter(w -> matchesType(w.getTriggerJson(), expectedType))
                .toList();
    }

    /** 从 trigger_json 解析 type 并匹配。 */
    boolean matchesType(String triggerJson, String expectedType) {
        try {
            Map<String, Object> trigger = objectMapper.readValue(
                    triggerJson, new TypeReference<Map<String, Object>>() {});
            Object type = trigger.get("type");
            return type != null && expectedType.equals(type.toString());
        } catch (Exception e) {
            log.warn("解析 trigger_json 失败: {}", e.getMessage());
            return false;
        }
    }

    /** RecordChangeEvent.ChangeType → trigger_json.type。 */
    static String mapEventToTriggerType(RecordChangeEvent.ChangeType changeType) {
        return switch (changeType) {
            case CREATE -> "on_create";
            case UPDATE -> "on_update";
            case DELETE -> "on_delete";
        };
    }
}
