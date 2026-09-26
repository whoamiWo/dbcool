package com.nocobase.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.config.AsyncTask;
import com.nocobase.config.AsyncTaskPublishException;
import com.nocobase.config.AsyncTaskPublisher;
import com.nocobase.event.RecordChangeEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PHASE 55 Stage 2 — Webhook 出口推送服务。
 *
 * <p>数据变更事件 → 匹配订阅 → 发布到 RabbitMQ 异步任务队列。
 * 由 {@code WebhookTaskHandler} 真正执行 HTTP POST,
 * 失败自动退避重试,超限进入死信队列。
 *
 * <p>改造前:同步 POST,失败仅日志(丢失风险)。
 * 改造后:发布到 MQ,失败可补偿,企业数据一致性保障。
 */
@Service
public class WebhookSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriptionService.class);
    static final String TASK_TYPE = "webhook.dispatch";

    private final WebhookSubscriptionRepository repository;
    private final AsyncTaskPublisher publisher;

    public WebhookSubscriptionService(WebhookSubscriptionRepository repository,
                                       AsyncTaskPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
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
            publish(sub, event, eventName);
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

    private void publish(WebhookSubscriptionEntity sub, RecordChangeEvent event, String eventName) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("targetUrl", sub.getTargetUrl());
            payload.put("secret", sub.getSecret());
            payload.put("event", eventName);
            payload.put("collection", event.getCollectionName());
            payload.put("recordId", event.getRecordId() == null ? "" : event.getRecordId());
            payload.put("data", event.getData() == null ? Map.of() : event.getData());
            payload.put("occurredAt", Instant.now().toString());

            AsyncTask task = new AsyncTask(
                    TASK_TYPE,
                    sub.getId().toString(),
                    event.getTenantId(),
                    payload,
                    event.getUserId(),
                    event.getTenantId() + ":" + eventName
            );
            publisher.publish(task);
            log.debug("[webhook] 任务已发布 MQ → {} ({})", sub.getTargetUrl(), eventName);
        } catch (AsyncTaskPublishException e) {
            // MQ 不可达:拒绝放行,调用方事务回滚
            log.error("[webhook] MQ 不可达,拒绝放行订阅 {} → {}", sub.getId(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("[webhook] 构建任务失败,拒绝放行订阅 {}: {}", sub.getId(), e.getMessage(), e);
            throw new IllegalStateException("Webhook 任务构建失败: " + sub.getId(), e);
        }
    }

    /** HMAC-SHA256 签名(十六进制)。 */
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
