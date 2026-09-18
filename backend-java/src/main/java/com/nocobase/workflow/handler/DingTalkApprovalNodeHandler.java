package com.nocobase.workflow.handler;

import com.nocobase.integration.dingtalk.DingTalkApprovalService;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 钉钉 OA 审批节点处理器 — DINGTALK_APPROVAL 节点类型。
 *
 * <p><b>Week 3 任务</b>: 工作流节点触发钉钉审批，等待回调推进。
 * <p>执行成功后继续下一个节点（CONTINUE）。
 */
@Component
public class DingTalkApprovalNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(DingTalkApprovalNodeHandler.class);

    private final DingTalkApprovalService approvalService;

    public DingTalkApprovalNodeHandler(DingTalkApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Override
    public String type() {
        return "DINGTALK_APPROVAL";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        Map<String, Object> node = ctx.node();
        String processCode = (String) node.get("process_code");
        String title = (String) node.get("title");

        if (processCode == null || processCode.isBlank()) {
            log.warn("[DINGTALK_APPROVAL] missing process_code in node config");
            return NodeOutcome.CONTINUE;
        }

        String instanceId = approvalService.createApproval(processCode, title, node);
        if (instanceId != null) {
            log.info("[DINGTALK_APPROVAL] 审批实例已创建: {} (title={})", instanceId, title);
        } else {
            log.warn("[DINGTALK_APPROVAL] 审批实例创建失败: {}", title);
        }

        return NodeOutcome.CONTINUE;
    }
}