package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 钉钉审批回调控制器 — 接收钉钉服务器推送的审批结果。
 *
 * <p><b>Week 3 任务</b>: 处理钉钉 OA 审批回调，更新工作流节点状态。
 * <p>钉钉推送字段: {@code instance_id / result(agree|refuse) / finish_time}
 */
@RestController
@RequestMapping("/api/dingtalk/callback")
public class DingTalkCallbackController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkCallbackController.class);

    private final DingTalkApprovalService approvalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DingTalkCallbackController(DingTalkApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * 钉钉 OA 审批回调入口。
     *
     * @param payload 钉钉推送的审批数据
     * @return 接收确认
     */
    @PostMapping("/approval")
    public ResponseEntity<Map<String, Object>> onApprovalCallback(
            @RequestBody Map<String, Object> payload) {
        try {
            String instanceId = String.valueOf(payload.get("instance_id"));
            String result = String.valueOf(payload.get("result"));

            log.info("[DingTalk] 审批回调: instance={}, result={}", instanceId, result);

            // 验证签名 (钉钉推送包含 sign 字段)
            if (!verifySignature(payload)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", 401, "message", "签名验证失败"));
            }

            // 更新工作流节点状态
            approvalService.handleApprovalCallback(instanceId, result);

            return ResponseEntity.ok(Map.of("code", 0, "message", "received"));

        } catch (Exception e) {
            log.error("[DingTalk] 审批回调处理失败: {}", e.getMessage(), e);
            return ResponseEntity.status(502)
                    .body(Map.of("code", 502, "message", "回调处理失败: " + e.getMessage()));
        }
    }

    /**
     * 验证钉钉推送签名。
     */
    private boolean verifySignature(Map<String, Object> payload) {
        // 钉钉推送包含 sign 字段，使用应用密钥验证
        String sign = String.valueOf(payload.get("sign"));
        if (sign == null || sign.isBlank()) {
            log.warn("[DingTalk] 回调缺少签名");
            return false;
        }
        // 简化验证: 实际应使用 HMAC-SHA256 验证
        return true;
    }
}