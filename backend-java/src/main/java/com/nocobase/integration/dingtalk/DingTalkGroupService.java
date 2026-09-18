package com.nocobase.integration.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 钉钉群消息推送服务 — 支持工作流通知到钉钉群。
 *
 * <p>复用现有 {@link com.nocobase.notification.DingTalkDispatcher}，
 * 扩展工作流事件驱动的消息推送。
 */
@Service
public class DingTalkGroupService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkGroupService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${dingtalk.app-key:}")
    private String appKey;

    @Value("${dingtalk.app-secret:}")
    private String appSecret;

    /**
     * 发送群消息 (webhook 方式)
     * @param webhookUrl 钉钉群机器人 Webhook
     * @param message 消息内容
     */
    public void sendToGroup(String webhookUrl, String message) {
        Map<String, Object> payload = Map.of(
                "msgtype", "text",
                "text", Map.of("content", message)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(
                toJson(payload), headers
        );

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(webhookUrl, entity, String.class);
            log.info("[DingTalk] Group message sent: status={}", resp.getStatusCode());
        } catch (Exception e) {
            log.error("[DingTalk] Failed to send group message: {}", e.getMessage());
        }
    }

    /**
     * 发送富文本消息到群
     */
    public void sendRichMessage(String webhookUrl, String title, String content) {
        Map<String, Object> payload = Map.of(
                "msgtype", "markdown",
                "markdown", Map.of("title", title, "text", content)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(toJson(payload), headers);

        try {
            restTemplate.postForEntity(webhookUrl, entity, String.class);
        } catch (Exception e) {
            log.error("[DingTalk] Failed to send rich message: {}", e.getMessage());
        }
    }

    /**
     * 工作流通知 → 钉钉群
     * @param workflowInstanceId 工作流实例 ID
     * @param nodeType 节点类型
     * @param title 通知标题
     * @param body 通知正文
     * @param webhookUrl 群机器人 Webhook
     */
    public void notifyWorkflowEvent(String workflowInstanceId, String nodeType,
                                     String title, String body, String webhookUrl) {
        String message = String.format(
                "### 工作流通知\n" +
                "**实例 ID:** %s\n" +
                "**节点:** %s\n" +
                "**内容:** %s\n",
                workflowInstanceId, nodeType, body
        );
        sendRichMessage(webhookUrl, title, message);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize: {}", e.getMessage());
            return "{}";
        }
    }
}
