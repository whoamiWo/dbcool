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
 * Webhook 渠道 — POST JSON 到任意 URL.
 * 配置示例:
 *   config: {
 *     "url": "https://example.com/hook",
 *     "method": "POST",                 // POST / PUT (默认 POST)
 *     "headers": { "X-Auth": "..." },   // 自定义 headers
 *     "timeout_seconds": 5,
 *     "secret": "..."                    // 可选,加 HMAC-SHA256 签名
 *   }
 */
@Component
public class WebhookDispatcher implements NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public NotificationChannelEntity.Type supportedType() {
        return NotificationChannelEntity.Type.WEBHOOK;
    }

    @Override
    public SendResult send(NotificationChannelEntity channel, String recipient, Map<String, Object> payload) {
        Map<String, Object> cfg = channel.getConfig();
        String url = (String) cfg.getOrDefault("url", recipient);
        if (url == null || url.isBlank()) {
            return SendResult.error("webhook url missing");
        }
        String method = String.valueOf(cfg.getOrDefault("method", "POST")).toUpperCase();
        int timeoutSec = ((Number) cfg.getOrDefault("timeout_seconds", 5)).intValue();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", payload.getOrDefault("title", ""));
        body.put("body", payload.getOrDefault("body", ""));
        body.put("channel", channel.getName());
        body.put("type", channel.getType().name());
        body.put("ts", System.currentTimeMillis());
        if (payload.get("data") instanceof Map<?, ?> d) {
            body.put("data", d);
        }
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            return SendResult.error("serialize: " + e.getMessage());
        }

        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "nocobase-notifier/1.0");
            @SuppressWarnings("unchecked")
            Map<String, String> headers = (Map<String, String>) cfg.get("headers");
            if (headers != null) {
                headers.forEach(rb::header);
            }
            HttpRequest.BodyPublisher pub = HttpRequest.BodyPublishers.ofString(json);
            HttpRequest req = "PUT".equals(method)
                    ? rb.PUT(pub).build()
                    : rb.POST(pub).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc >= 200 && sc < 300) {
                return SendResult.ok("HTTP " + sc);
            }
            return SendResult.error("HTTP " + sc + ": " + truncate(resp.body(), 200));
        } catch (Exception e) {
            log.warn("webhook send failed url={} err={}", url, e.getMessage());
            return SendResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
}
