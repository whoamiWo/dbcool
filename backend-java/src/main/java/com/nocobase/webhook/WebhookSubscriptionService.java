package com.nocobase.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.event.RecordChangeEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Webhook 出口推送服务(Week 41 复核 D5.3)。
 *
 * <p>数据变更事件 → 匹配订阅 → POST 到外部 URL。
 * 配了 {@code secret} 时会带 {@code X-Webhook-Signature}(HMAC-SHA256)便于接收方验签。
 *
 * <p><strong>失败语义</strong>:Webhook 是尽力而为的旁路通知,推送失败仅记日志,
 * 不影响业务主流程(不做重试队列 —— 重试与死信留待 Week 42+)。
 */
@Service
public class WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);

    private final WebhookSubscriptionRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public WebhookSubscriptionService(WebhookSubscriptionRepository repository) {
        this.repository = repository;
    }

    /** 把记录变更事件分发给所有匹配的订阅。 */
    public void dispatch(RecordChangeEvent event) {
        String eventName = mapEventName(event.getChangeType());
        if (eventName == null) return;

        List<WebhookSubscriptionEntity> subs =
                repository.findByTenantIdAndCollectionNameAndEventAndEnabledTrue(
                        event.getTenantId(), event.getCollectionName(), eventName);
        if (subs.isEmpty()) return;

        log.debug("[webhook] 事件 {} 匹配 {} 个订阅 (collection={})",
                eventName, subs.size(), event.getCollectionName());
        for (WebhookSubscriptionEntity sub : subs) {
            send(sub, event, eventName);
        }
    }

    /** ChangeType → 订阅事件名;无法映射返回 null。 */
    private static String mapEventName(RecordChangeEvent.ChangeType type) {
        if (type == null) return null;
        return switch (type) {
            case CREATE -> "on_create";
            case UPDATE -> "on_update";
            case DELETE -> "on_delete";
        };
    }

    private void send(WebhookSubscriptionEntity sub, RecordChangeEvent event, String eventName) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "event", eventName,
                    "collection", event.getCollectionName(),
                    "recordId", event.getRecordId() == null ? "" : event.getRecordId(),
                    "data", event.getData() == null ? Map.of() : event.getData(),
                    "occurredAt", Instant.now().toString()
            ));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (sub.getSecret() != null && !sub.getSecret().isBlank()) {
                headers.set("X-Webhook-Signature", hmacSha256(sub.getSecret(), body));
            }

            restTemplate.exchange(sub.getTargetUrl(), HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            log.info("[webhook] 推送成功 → {} ({})", sub.getTargetUrl(), eventName);
        } catch (RestClientException e) {
            log.warn("[webhook] 推送失败 → {} : {}", sub.getTargetUrl(), e.getMessage());
        } catch (Exception e) {
            log.warn("[webhook] 序列化/签名失败 → {} : {}", sub.getTargetUrl(), e.getMessage());
        }
    }

    /** HMAC-SHA256 签名(十六进制)。 */
    private static String hmacSha256(String secret, String payload) {
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
