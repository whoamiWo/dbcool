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
    private final com.nocobase.workflow.WorkflowInstanceRepository instanceRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public DingTalkApprovalNodeHandler(DingTalkApprovalService approvalService,
                                       com.nocobase.workflow.WorkflowInstanceRepository instanceRepository) {
        this.approvalService = approvalService;
        this.instanceRepository = instanceRepository;
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
            // Wave4 待办池 #10：绑定 —— 钉钉审批实例 ID 写入工作流实例的 trigger_data_json，
            // 回调时据此反查实例并推进任务（此前 instanceId 直接丢弃 → 回调无法关联，闭环断裂）。
            bindDingTalkInstance(ctx, instanceId);
            log.info("[DINGTALK_APPROVAL] 审批实例已创建并绑定: {} (title={})", instanceId, title);
        } else {
            log.warn("[DINGTALK_APPROVAL] 审批实例创建失败: {}", title);
        }

        return NodeOutcome.CONTINUE;
    }

    /**
     * 把钉钉审批实例 ID 绑定到工作流实例（复用 trigger_data_json 字段，不新增数据表）。
     */
    private void bindDingTalkInstance(NodeExecutionContext ctx, String dingtalkInstanceId) {
        try {
            var instance = ctx.instance();
            if (instance == null) {
                return;
            }
            String raw = instance.getTriggerDataJson();
            String json = (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) ? "{}" : raw.trim();
            com.fasterxml.jackson.databind.JsonNode parsed = mapper.readTree(json);
            com.fasterxml.jackson.databind.node.ObjectNode obj = parsed.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) parsed
                    : mapper.createObjectNode();
            obj.put("dingtalkInstanceId", dingtalkInstanceId);
            instance.setTriggerDataJson(mapper.writeValueAsString(obj));
            instanceRepository.save(instance);
        } catch (Exception e) {
            log.error("[DINGTALK_APPROVAL] 绑定钉钉实例失败（回调将无法关联）instance={} err={}",
                    dingtalkInstanceId, e.getMessage());
        }
    }
}