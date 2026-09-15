package com.nocobase.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 工作流节点处理器注册表(Week 41 D4b.1 — 策略化).
 *
 * <p>Spring 启动时自动注入所有 {@link WorkflowNodeHandler} bean,
 * 按 type 索引。新增节点类型只需加 handler,无需改注册表。
 */
@Component
public class WorkflowNodeHandlerRegistry {

    private final Map<String, WorkflowNodeHandler> byType = new HashMap<>();

    public WorkflowNodeHandlerRegistry(List<WorkflowNodeHandler> handlers) {
        for (WorkflowNodeHandler h : handlers) {
            String key = h.type() == null ? "" : h.type().toUpperCase();
            WorkflowNodeHandler prev = byType.put(key, h);
            if (prev != null) {
                throw new IllegalStateException("重复注册节点 handler: " + key
                        + " (" + prev.getClass().getSimpleName() + " vs "
                        + h.getClass().getSimpleName() + ")");
            }
        }
    }

    /**
     * 按节点 type 字符串查找 handler(大小写不敏感)。
     * 返回 Optional — 未注册节点不会 crash,留给引擎走 unknown 分支。
     */
    public Optional<WorkflowNodeHandler> find(String type) {
        if (type == null) return Optional.empty();
        return Optional.ofNullable(byType.get(type.toUpperCase()));
    }

    /** 测试用:列出所有注册的 type。 */
    public List<String> registeredTypes() {
        return List.copyOf(byType.keySet());
    }
}
