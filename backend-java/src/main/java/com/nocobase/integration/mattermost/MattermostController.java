package com.nocobase.integration.mattermost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;

import java.util.Map;

/**
 * Mattermost 集成 REST API。
 *
 * <p>提供：
 * <ul>
 *   <li>Webhook 接收（Incoming Webhooks）</li>
 *   <li>消息发送（Outgoing Webhooks/API）</li>
 * </ul>
 */
@RestController
@Tag(name = "Mattermost Integration", description = "Mattermost 集成")
@RequestMapping("/api/mattermost")
public class MattermostController {

    private final MattermostAppService mattermostAppService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MattermostController(MattermostAppService mattermostAppService) {
        this.mattermostAppService = mattermostAppService;
    }

    /** 获取 Mattermost Incoming Webhook URL */
    @GetMapping("/webhook-url")
    public ResponseEntity<Map<String, String>> getWebhookUrl() {
        String webhookUrl = mattermostAppService.getIncomingWebhookUrl();
        return ResponseEntity.ok(Map.of("webhookUrl", webhookUrl));
    }

    /** Incoming Webhook 回调：接收外部消息 */
    @PostMapping("/webhook/incoming")
    public ResponseEntity<String> incomingWebhook(
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-Mattermost-Token", required = false) String token
    ) {
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest().body("{\"text\":\"empty body\"}");
        }

        try {
            // 验证 token（如果配置了）
            if (!mattermostAppService.verifyWebhookToken(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"text\":\"unauthorized\"}");
            }

            // 解析并处理消息
            JsonNode json = objectMapper.readTree(body);
            mattermostAppService.handleIncomingMessage(json);
            
            return ResponseEntity.ok("{\"text\":\"ok\"}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"text\":\"error\"}");
        }
    }

    /** 发送消息到 Mattermost 频道 */
    @PostMapping("/message/send")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String channelId = (String) body.get("channel_id");
        String message = (String) body.get("message");
        
        if (channelId == null || message == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "channel_id 和 message 必填"));
        }
        if (!mattermostAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "Mattermost 应用未配置"));
        }
        try {
            Map<String, Object> result = mattermostAppService.sendMessage(channelId, message);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "Mattermost 发送异常：" + e.getMessage()));
        }
    }

    /** 回复线程消息（Mattermost 支持 root_id） */
    @PostMapping("/message/reply")
    public ResponseEntity<Map<String, Object>> replyMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String channelId = (String) body.get("channel_id");
        String rootId = (String) body.get("root_id");
        String message = (String) body.get("message");
        
        if (channelId == null || rootId == null || message == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "channel_id/root_id/message 必填"));
        }
        if (!mattermostAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "Mattermost 应用未配置"));
        }
        try {
            Map<String, Object> result = mattermostAppService.replyMessage(channelId, rootId, message);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "Mattermost 发送异常：" + e.getMessage()));
        }
    }
}
