package com.nocobase.workflow.handler;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import org.springframework.stereotype.Component;

/** 外部审批节点只表达等待，不做 HTTP、不创建本地人工待办，也不把外推失败当作驳回。 */
@Component
public class DingTalkApprovalNodeHandler implements WorkflowNodeHandler {
    @Override
    public String type() { return "DINGTALK_APPROVAL"; }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        return NodeOutcome.NEEDS_APPROVAL;
    }
}
