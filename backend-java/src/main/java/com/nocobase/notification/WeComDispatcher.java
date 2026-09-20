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
 * 企业微信自定义机器人 webhook 通知器。
 * config: {
 *   "webhook_url": "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."
 * }
 * payload.data.markdown.title / markdown.text 会被渲染为 markdown 消息。
 */
@Component
public class WeComDispatcher implements NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WeComDispatcher.class);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public NotificationChannelEntity.Type supportedType() {
        return NotificationChannelEntity.Type.WE_COM;
    }

    @Override
    public SendResult send(NotificationChannelEntity channel, String recipient, Map<String, Object> payload) {
        Map<String, Object> cfg = channel.getConfig();
        String url = (String) cfg.get("webhook_url");
        if (url == null || url.isBlank()) {
            return SendResult.error("wecom webhook_url missing");
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            Object data = payload.get("data");
            if (data instanceof Map) {
                Map<?, ?> dataMap = (Map<?, ?>) data;
                if ("markdown".equalsIgnoreCase((String) dataMap.get("type"))) {
                    body.put("msgtype", "markdown");
                    Map<String, String> md = new LinkedHashMap<>();
                    md.put("content", (String) dataMap.get("text"));
                    body.put("markdown", md);
                } else {
                    body.put("msgtype", "text");
                    body.put("text", dataMap);
                }
            } else {
                body.put("msgtype", "text");
                Map<String, String> txt = new LinkedHashMap<>();
                txt.put("content", recipient);
                body.put("text", txt);
            }

            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                log.info("[WeCom] webhook sent: {}", resp.body());
                return SendResult.ok(resp.body());
            } else {
                log.warn("[WeCom] webhook failed: {} {}", resp.statusCode(), resp.body());
                return SendResult.error("http " + resp.statusCode());
            }
        } catch (Exception e) {
            log.error("[WeCom] webhook error", e);
            return SendResult.error(e.getMessage());
        }
    }
}