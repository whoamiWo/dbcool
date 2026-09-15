package com.nocobase.workflow;

import com.nocobase.event.RecordChangeEvent;
import java.util.Map;
import java.util.UUID;

/**
 * 节点执行上下文(Week 41 D4b).
 *
 * <p>封装 handler 执行所需的所有信息。
 */
public record NodeExecutionContext(
        WorkflowInstanceEntity instance,
        Map<String, Object> node,
        UUID defaultAssignee,
        /** 触发此次执行的事件(可为 null — API 手动 trigger 场景)。 */
        RecordChangeEvent triggeringEvent
) {}
