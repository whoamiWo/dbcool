package com.nocobase.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.config.AsyncTask;
import com.nocobase.config.AsyncTaskHandler;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * PHASE 55 Stage 2 — Webhook HTTP 推送任务消费器。
 *
 * <p>真正执行 POST 到外部 URL,带 HMAC 签名。
 * 失败抛异常 → RabbitMQ 死信机制进重试队列 → 退避后重试 → 超限进死信队列。
 */
@Component
public class WebhookTaskHandler implements AsyncTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookTaskHandler.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void handle(AsyncTask task) {
        Map<String, Object> payload = task.getPayload();
        String targetUrl = (String) payload.get("targetUrl");
        String secret = (String) payload.get("secret");
        String eventName = (String) payload.get("event");
        String body = buildBody(payload);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (secret != null && !secret.isBlank()) {
                headers.set("X-Webhook-Signature", hmacSha256(secret, body));
            }
            restTemplate.exchange(targetUrl, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            log.info("[webhook] 推送成功 → {} ({})", targetUrl, eventName);
        } catch (RestClientException e) {
            log.warn("[webhook] 推送失败 → {} : {}", targetUrl, e.getMessage());
            throw new RuntimeException("Webhook 推送失败,转重试: " + targetUrl, e);
        }
    }

    @Override
    public java.util.List<String> supportedTypes() {
        return java.util.List.of(WebhookSubscriptionService.TASK_TYPE);
    }

    private String buildBody(Map<String, Object> payload) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("event", payload.get("event"));
        body.put("collection", payload.get("collection"));
        body.put("recordId", payload.get("recordId"));
        body.put("data", payload.get("data"));
        body.put("occurredAt", payload.get("occurredAt"));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Webhook body 序列化失败", e);
        }
    }

    static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}