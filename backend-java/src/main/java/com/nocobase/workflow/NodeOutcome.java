package com.nocobase.workflow;

/**
 * 节点执行结果(Week 41 D4b).
 *
 * <p>对齐原 WorkflowEngine.NodeResult 但语义更清晰。
 */
public enum NodeOutcome {
    /** 继续执行下一节点 */
    CONTINUE,
    /** 暂停等待审批 */
    NEEDS_APPROVAL,
    /** 执行失败(终止工作流) */
    FAILED,
    /** 节点被跳过(例如条件 false 分支) */
    SKIPPED
}
