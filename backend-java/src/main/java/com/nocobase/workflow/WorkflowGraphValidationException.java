package com.nocobase.workflow;

/**
 * 工作流图校验失败异常(Week 42 D4b.2 — R08 死循环防护).
 *
 * <p>触发场景:
 * <ul>
 *   <li>节点数超过 {@link WorkflowGraphValidator#MAX_NODES}</li>
 *   <li>边引用了不存在的节点</li>
 *   <li>图存在环(DFS 三色标记检测到 gray 节点被再次访问)</li>
 * </ul>
 *
 * <p>Controller 捕获后应返 400 + 错误消息,用户能据此调整配置。
 */
public class WorkflowGraphValidationException extends RuntimeException {

    public WorkflowGraphValidationException(String message) {
        super(message);
    }
}
