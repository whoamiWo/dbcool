package com.nocobase.integration.mattermost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.common.InboundMessageService;
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

    @Value("${mattermost.default-tenant-id:tenant_default}")
    private String defaultTenantId;

    @Value("${mattermost.default-channel-id:}")
    private String defaultChannelId;

    @Value("${integration.mattermost.require-token:true}")
    private boolean requireToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InboundMessageService inboundMessageService;

    public MattermostAppService(InboundMessageService inboundMessageService) {
        this.inboundMessageService = inboundMessageService;
    }

    public String getIncomingWebhookUrl() {
        return incomingWebhookUrl;
    }

    public boolean isConfigured() {
        return siteUrl != null && !siteUrl.isBlank() &&
               botToken != null && !botToken.isBlank();
    }

    public boolean verifyWebhookToken(String token) {
        if (requireToken && (webhookToken == null || webhookToken.isBlank())) {
            log.warn("[Mattermost] Webhook 校验：requireToken=true 但未配置 webhook-token，拒绝请求");
            return false; // T3 修复：fail-close
        }
        if (webhookToken == null || webhookToken.isBlank()) {
            return true; // 未配置 token 且 requireToken=false 时不验证
        }
        return webhookToken.equals(token);
    }

    public void handleIncomingMessage(JsonNode json) {
        String text = json.path("text").asText("");
        String channelId = json.path("channel_id").asText("");
        String userId = json.path("user_id").asText("");
        String postId = json.path("id").asText(json.path("post_id").asText(""));

        // 使用 InboundMessageService 进行落库 + 广播
        if (inboundMessageService != null && !postId.isBlank()) {
            inboundMessageService.processInboundMessage(
                    defaultTenantId,
                    null, // 使用 defaultChannelId
                    "mattermost",
                    postId,
                    userId,
                    text,
                    null
            );
        } else {
            log.warn("[Mattermost] 入站消息无法处理：缺少 postId 或 inboundMessageService=null");
        }
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