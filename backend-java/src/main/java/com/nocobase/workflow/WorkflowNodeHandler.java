package com.nocobase.workflow;

import com.nocobase.event.RecordChangeEvent;
import java.util.Map;

/**
 * 工作流节点处理器(Week 41 D4b — 节点策略化).
 *
 * <p>每个节点类型(APPROVAL / NOTIFICATION / CONDITION / HTTP / DATA_UPDATE)
 * 实现一个 handler,引擎通过 handler type 字符串查找并执行。
 *
 * <p>新增节点类型只需:
 * <ol>
 *   <li>实现本接口</li>
 *   <li>注册到 {@link WorkflowNodeHandlerRegistry}(@Component 自动扫描)</li>
 *   <li>无需改 WorkflowEngine 主代码</li>
 * </ol>
 *
 * <p>Week 41 D4b 范围:实现 APPROVAL / NOTIFICATION / CONDITION / HTTP 4 个节点策略化。
 * DATA_UPDATE / SUB_WORKFLOW / SCRIPT 节点类型留在 Week 42+ D4b.5 实现。
 */
public interface WorkflowNodeHandler {

    /**
     * 节点类型标识,与 node.type 字符串匹配(大小写不敏感)。
     * 例如 "APPROVAL" / "NOTIFICATION"。
     */
    String type();

    /**
     * 执行节点。
     *
     * @param ctx 执行上下文(实例 + 节点定义 + 默认 assignee + 触发事件)
     * @return 执行结果(决定下一步走向)
     */
    NodeOutcome execute(NodeExecutionContext ctx);
}
