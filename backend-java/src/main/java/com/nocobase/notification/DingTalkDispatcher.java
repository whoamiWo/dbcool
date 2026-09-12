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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 钉钉自定义机器人 webhook.
 * config: {
 *   "webhook_url": "https://oapi.dingtalk.com/robot/send?access_token=...",
 *   "secret": "SEC..."           // 可选,加签
 * }
 * payload.data.markdown.title / markdown.text 会被渲染为 markdown 消息。
 */
@Component
public class DingTalkDispatcher implements NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DingTalkDispatcher.class);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public NotificationChannelEntity.Type supportedType() {
        return NotificationChannelEntity.Type.DINGTALK;
    }

    @Override
    public SendResult send(NotificationChannelEntity channel, String recipient, Map<String, Object> payload) {
        Map<String, Object> cfg = channel.getConfig();
        String url = (String) cfg.getOrDefault("webhook_url", recipient);
        if (url == null || url.isBlank()) return SendResult.error("dingtalk webhook_url missing");

        String secret = (String) cfg.get("secret");
        String finalUrl = url;
        if (secret != null && !secret.isBlank()) {
            try {
                long ts = System.currentTimeMillis();
                String stringToSign = ts + "\n" + secret;
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
                String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(signData), StandardCharsets.UTF_8);
                finalUrl = url + (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + sign;
            } catch (Exception e) {
                return SendResult.error("sign: " + e.getMessage());
            }
        }

        String title = String.valueOf(payload.getOrDefault("title", "通知"));
        String body = String.valueOf(payload.getOrDefault("body", ""));

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("msgtype", "markdown");
        Map<String, String> md = new LinkedHashMap<>();
        md.put("title", title);
        md.put("text", "### " + title + "\n\n" + body);
        msg.put("markdown", md);

        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return SendResult.error("serialize: " + e.getMessage());
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
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
            log.warn("dingtalk send failed err={}", e.getMessage());
            return SendResult.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() > max ? s.substring(0, max) + "..." : s);
    }
}
