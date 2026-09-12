package com.nocobase.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企业微信自定义机器人 webhook.
 * config: {
 *   "webhook_url": "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
 * }
 * 消息格式 markdown.content.
 */
@Component
public class WeChatWorkDispatcher implements NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WeChatWorkDispatcher.class);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public NotificationChannelEntity.Type supportedType() {
        return NotificationChannelEntity.Type.WECHAT_WORK;
    }

    @Override
    public SendResult send(NotificationChannelEntity channel, String recipient, Map<String, Object> payload) {
        Map<String, Object> cfg = channel.getConfig();
        String url = (String) cfg.getOrDefault("webhook_url", recipient);
        if (url == null || url.isBlank()) return SendResult.error("wechat_work webhook_url missing");

        String title = String.valueOf(payload.getOrDefault("title", "通知"));
        String body = String.valueOf(payload.getOrDefault("body", ""));

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("msgtype", "markdown");
        Map<String, String> md = new LinkedHashMap<>();
        md.put("content", "## " + title + "\n\n" + body);
        msg.put("markdown", md);

        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return SendResult.error("serialize: " + e.getMessage());
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return SendResult.ok("HTTP 200: " + truncate(resp.body(), 150));
            }
            return SendResult.error("HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
        } catch (Exception e) {
            log.warn("wechat_work send failed err={}", e.getMessage());
            return SendResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
}
