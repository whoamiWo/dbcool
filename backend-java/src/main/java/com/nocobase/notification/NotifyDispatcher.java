package com.nocobase.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.workflow.MessageEntity;
import com.nocobase.workflow.MessageRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知分发器（E3）。
 *
 * <p>职责单一且集中：
 * <ol>
 *   <li><b>站内必达</b>：写 {@code messages}（通知中心的数据源，D2）。</li>
 *   <li><b>外部渠道按偏好</b>：复用 {@link NotificationService} 的
 *       EMAIL/WEBHOOK/DINGTALK/WECHAT_WORK；投递失败只记 {@code channel_sent}，不阻断主流程。</li>
 *   <li><b>免打扰</b>：{@code user_preferences.preferences.notify.quietHours} 时段内
 *       <b>不投递外部渠道，但站内仍留档</b>。</li>
 *   <li><b>聚合</b>：同 {@code digest_key} 10 分钟内合并为一条并累加 {@code digest_count}。</li>
 * </ol>
 */
@Service
public class NotifyDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotifyDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final long DIGEST_WINDOW_MINUTES = 10;

    // 7 类通知模板（任务书 T4.1）
    public static final String APPROVAL_TODO = "APPROVAL_TODO";
    public static final String APPROVAL_RESULT = "APPROVAL_RESULT";
    public static final String MENTION = "MENTION";
    public static final String COMMENT = "COMMENT";
    public static final String DUE_SOON = "DUE_SOON";
    public static final String WORKFLOW_FAILED = "WORKFLOW_FAILED";
    public static final String QUOTA = "QUOTA";
    public static final String SYSTEM = "SYSTEM";

    private final MessageRepository messageRepository;
    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;

    public NotifyDispatcher(MessageRepository messageRepository,
                            NotificationService notificationService,
                            JdbcTemplate jdbc) {
        this.messageRepository = messageRepository;
        this.notificationService = notificationService;
        this.jdbc = jdbc;
    }

    /** 通知载荷。 */
    public record Payload(
            String tenantId,
            String title,
            String body,
            String relatedId,
            String deeplink,
            String priority,
            String digestKey
    ) {
        public Payload(String tenantId, String title, String body, String deeplink) {
            this(tenantId, title, body, null, deeplink, "NORMAL", null);
        }
    }

    /**
     * 向多个收件人发通知。站内写入失败会抛（业务上必须留档）；
     * 外部渠道失败只记录，不影响返回值。
     */
    @Transactional
    public List<MessageEntity> notify(String type, Collection<UUID> recipientIds, Payload payload) {
        if (recipientIds == null || recipientIds.isEmpty()) return List.of();
        List<MessageEntity> written = new ArrayList<>();
        for (UUID recipient : new LinkedHashSet<>(recipientIds)) {
            if (recipient == null) continue;
            written.add(notifyOne(type, recipient, payload));
        }
        return written;
    }

    @Transactional
    public MessageEntity notifyOne(String type, UUID recipient, Payload payload) {
        String tenantId = payload.tenantId();
        String digestKey = payload.digestKey();
        MessageEntity msg = null;

        if (digestKey != null && !digestKey.isBlank()) {
            Instant since = Instant.now().minus(DIGEST_WINDOW_MINUTES, ChronoUnit.MINUTES);
            List<MessageEntity> existing = messageRepository
                    .findByTenantIdAndRecipientAndDigestKeyAndCreatedAtAfterOrderByCreatedAtDesc(
                            tenantId, recipient, digestKey, since, PageRequest.of(0, 1));
            if (!existing.isEmpty()) {
                msg = existing.get(0);
                msg.setDigestCount(msg.getDigestCount() + 1);
                msg.setTitle(payload.title());
                msg.setBody(payload.body());
                msg.setDeeplink(payload.deeplink());
                msg.setCreatedAt(Instant.now());
                msg.setRead(false);
                msg.setReadAt(null);
            }
        }

        boolean created = false;
        if (msg == null) {
            msg = new MessageEntity();
            msg.setId(UUID.randomUUID());
            msg.setRecipient(recipient);
            msg.setTenantId(tenantId);
            msg.setType(type);
            msg.setTitle(payload.title());
            msg.setBody(payload.body());
            msg.setRelatedId(payload.relatedId());
            msg.setDeeplink(payload.deeplink());
            msg.setPriority(payload.priority() == null ? "NORMAL" : payload.priority());
            msg.setDigestKey(digestKey);
            msg.setDigestCount(1);
            msg.setCreatedAt(Instant.now());
            msg.setRead(false);
            created = true;
        }
        // 外部渠道（免打扰/偏好可能跳过）；仅新建消息时投递，聚合不重复打扰。
        // 先算投递结果再一次性落库，避免同一行两次写入。
        Map<String, Object> channelResult = created
                ? deliverToChannels(type, recipient, tenantId, payload)
                : Map.of("digest", "aggregated");
        try {
            msg.setChannelSentJson(MAPPER.writeValueAsString(channelResult));
        } catch (Exception e) {
            log.debug("[notify] channel_sent 序列化失败: {}", e.getMessage());
        }
        messageRepository.save(msg);
        return msg;
    }

    private Map<String, Object> deliverToChannels(String type, UUID recipient, String tenantId,
                                                  Payload payload) {
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> prefs = preferencesOf(recipient);
        if (inQuietHours(prefs)) {
            // 任务书 T4：免打扰时段不投递，但站内已留档
            result.put("skipped", "quiet_hours");
            return result;
        }
        if (!channelAllowed(prefs, type)) {
            result.put("skipped", "preference_off");
            return result;
        }
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("title", payload.title());
            event.put("body", payload.body());
            event.put("deeplink", payload.deeplink());
            event.put("type", type);
            var sends = notificationService.fire(tenantId, "notify." + type,
                    recipient.toString(), event);
            for (var r : sends) {
                result.put("channel", r.success() ? "success" : "error");
                result.put("detail", r.detail());
            }
            if (sends.isEmpty()) result.put("channels", "none_configured");
        } catch (Exception e) {
            // 外部渠道失败不影响站内留档（R5：通知风暴/渠道故障隔离）
            log.warn("[notify] 外部渠道投递失败 recipient={} type={}: {}",
                    recipient, type, e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    private Map<String, Object> preferencesOf(UUID userId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT preferences::text FROM user_preferences WHERE user_id = ?",
                    String.class, userId);
            if (rows.isEmpty() || rows.get(0) == null) return Map.of();
            return MAPPER.readValue(rows.get(0), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 读不到偏好 → 按默认（投递）处理，避免"配置故障 = 收不到通知"
            log.debug("[notify] 读取偏好失败 user={}: {}", userId, e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean inQuietHours(Map<String, Object> prefs) {
        Object notify = prefs.get("notify");
        if (!(notify instanceof Map<?, ?> n)) return false;
        Object qh = ((Map<String, Object>) n).get("quietHours");
        if (!(qh instanceof List<?> list) || list.size() < 2) return false;
        try {
            LocalTime start = LocalTime.parse(String.valueOf(list.get(0)));
            LocalTime end = LocalTime.parse(String.valueOf(list.get(1)));
            LocalTime now = LocalTime.now(ZONE);
            if (start.equals(end)) return false;
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            // 跨零点，如 22:00 → 08:00
            return !now.isBefore(start) || now.isBefore(end);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean channelAllowed(Map<String, Object> prefs, String type) {
        Object notify = prefs.get("notify");
        if (!(notify instanceof Map<?, ?> n)) return true;
        Object channels = ((Map<String, Object>) n).get(type);
        if (channels == null) return true;
        if (channels instanceof List<?> list) return !list.isEmpty();
        return Boolean.parseBoolean(String.valueOf(channels));
    }
}
