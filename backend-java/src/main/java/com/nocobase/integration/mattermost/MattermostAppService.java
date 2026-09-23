package com.nocobase.integration.mattermost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Mattermost 应用服务：Webhook 处理、消息发送。
 */
@Service
public class MattermostAppService {
    private static final Logger log = LoggerFactory.getLogger(MattermostAppService.class);

    @Value("${mattermost.site-url:}")
    private String siteUrl;

    @Value("${mattermost.bot-token:}")
    private String botToken;

    @Value("${mattermost.incoming-webhook-url:}")
    private String incomingWebhookUrl;

    @Value("${mattermost.webhook-token:}")
    private String webhookToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String getIncomingWebhookUrl() {
        return incomingWebhookUrl;
    }

    public boolean isConfigured() {
        return siteUrl != null && !siteUrl.isBlank() &&
               botToken != null && !botToken.isBlank();
    }

    public boolean verifyWebhookToken(String token) {
        if (webhookToken == null || webhookToken.isBlank()) {
            return true; // 未配置 token 时不验证
        }
        return webhookToken.equals(token);
    }

    public void handleIncomingMessage(JsonNode json) {
        String text = json.path("text").asText("");
        String channelId = json.path("channel_id").asText("");
        String userId = json.path("user_id").asText("");

        // 事件记录到日志，便于运营审计与 AI Copilot 异步消费
        log.info("[Mattermost] 入站消息: channel={}, user={}, text={}", channelId, userId, text);
    }

    public Map<String, Object> sendMessage(String channelId, String message) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("channel_id", channelId);
        payload.put("message", message);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + botToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                siteUrl + "/api/v4/posts",
                entity,
                JsonNode.class
        );

        JsonNode body = resp.getBody();
        if (body == null || body.path("id").isMissingNode()) {
            throw new RuntimeException("Mattermost 发送消息失败：" + (body != null ? body.toString() : "无响应"));
        }

        return Map.of(
                "code", 0,
                "message", "sent",
                "data", Map.of(
                        "post_id", body.path("id").asText(""),
                        "create_at", body.path("create_at").asLong(0)
                )
        );
    }

    public Map<String, Object> replyMessage(String channelId, String rootId, String message) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("channel_id", channelId);
        payload.put("message", message);
        payload.put("root_id", rootId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + botToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<JsonNode> resp = restTemplate.postForEntity(
                siteUrl + "/api/v4/posts",
                entity,
                JsonNode.class
        );

        JsonNode body = resp.getBody();
        if (body == null || body.path("id").isMissingNode()) {
            throw new RuntimeException("Mattermost 回复消息失败：" + (body != null ? body.toString() : "无响应"));
        }

        return Map.of(
                "code", 0,
                "message", "sent",
                "data", Map.of(
                        "post_id", body.path("id").asText(""),
                        "create_at", body.path("create_at").asLong(0)
                )
        );
    }
}