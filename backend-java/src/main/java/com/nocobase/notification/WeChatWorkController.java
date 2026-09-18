package com.nocobase.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 企业微信通知 REST API — 发送消息与校验配置。
 *
 * <p>当前基于企业微信自定义机器人 webhook(群机器人),
 * 支持 markdown / text / 卡片消息类型。
 * 如需应用消息(推送到用户/部门),可后续扩展 OAuth2 鉴权 + 消息推送接口。
 */
@RestController
@RequestMapping("/api/notifications/wechat-work")
public class WeChatWorkController {

    private static final Logger log = LoggerFactory.getLogger(WeChatWorkController.class);

    private final NotificationService notificationService;

    public WeChatWorkController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 发送企微消息。
     * body = {channelId, recipient, title, body, msgType}
     *
     * <p>msgType 可选:markdown(默认) / text / card。
     * 若 channel 未配置 webhook_url,recipient 可作备用地址。
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400, "message", "请求体不能为空"));
        }

        String channelId = String.valueOf(body.getOrDefault("channelId", ""));
        String recipient = String.valueOf(body.getOrDefault("recipient", ""));
        String title = String.valueOf(body.getOrDefault("title", "通知"));
        String content = String.valueOf(body.getOrDefault("body", ""));
        String msgType = String.valueOf(body.getOrDefault("msgType", "markdown"));

        if (channelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 400, "message", "channelId 必填(通知渠道配置了 webhook_url)"));
        }

        Map<String, Object> payload = Map.of(
                "title", title,
                "body", content,
                "msgType", msgType
        );

        NotificationDispatcher.SendResult result = notificationService.testSend(
                channelId.isBlank() ? null : UUID.fromString(channelId),
                recipient.isBlank() ? null : recipient,
                payload
        );

        if (result.success()) {
            return ResponseEntity.ok(Map.of(
                    "code", 0, "message", "sent",
                    "data", Map.of("detail", result.detail())));
        }
        return ResponseEntity.status(502).body(Map.of(
                "code", 502, "message", "发送失败: " + result.detail()));
    }

    /** 校验企微 webhook 可达性(HEAD 探活)。 */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping(@RequestParam String webhookUrl) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(webhookUrl))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .HEAD()
                    .build();
            java.net.http.HttpResponse<Void> resp = client.send(req,
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            return ResponseEntity.ok(Map.of(
                    "code", 0, "message", "reachable",
                    "data", Map.of("status", resp.statusCode())));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "code", 0, "message", "unreachable",
                    "data", Map.of("error", e.getMessage())));
        }
    }
}
