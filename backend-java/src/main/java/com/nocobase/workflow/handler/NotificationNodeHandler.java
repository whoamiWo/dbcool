package com.nocobase.workflow.handler;

import com.nocobase.notification.NotificationService;
import com.nocobase.workflow.MessageEntity;
import com.nocobase.workflow.MessageRepository;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowEntity;
import com.nocobase.workflow.WorkflowNodeHandler;
import com.nocobase.workflow.WorkflowRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通知节点 handler。
 *
 * <p>Week 41 复核修复:初版只有一行日志,若直接切换策略分发会静默丢失 legacy
 * {@code WorkflowEngine.logNotification()}(:262-309)的两项能力:
 * <ol>
 *   <li>写 {@code messages} 表站内信(MessageEntity)</li>
 *   <li>{@code notificationService.fire()} 多渠道推送(邮件/钉钉/企微/Webhook)</li>
 * </ol>
 * 本类现已按 legacy 逐项复刻,保证分发切换后行为等价。
 */
@Component
public class NotificationNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationNodeHandler.class);

    private final WorkflowRepository workflowRepository;
    private final MessageRepository messageRepository;
    private final NotificationService notificationService;

    public NotificationNodeHandler(
            WorkflowRepository workflowRepository,
            MessageRepository messageRepository,
            NotificationService notificationService
    ) {
        this.workflowRepository = workflowRepository;
        this.messageRepository = messageRepository;
        this.notificationService = notificationService;
    }

    @Override
    public String type() {
        return "NOTIFICATION";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());
        String title = (String) config.getOrDefault("title", "通知");
        // legacy 用 "message",内置模板用 "body" —— 两者都兼容
        String body = (String) config.getOrDefault("body",
                config.getOrDefault("message", "(no message)"));
        Object recipientObj = config.get("recipient");

        log.info("[workflow {} node {}] NOTIFICATION {} - {}",
                ctx.instance().getId(), ctx.node().get("id"), title, body);

        // 收件人:workflow 创建者(默认),config.recipient 可覆盖 —— 对齐 legacy
        UUID recipient = null;
        if (ctx.instance().getWorkflowId() != null) {
            recipient = workflowRepository.findById(ctx.instance().getWorkflowId())
                    .map(WorkflowEntity::getCreatedBy).orElse(null);
        }
        if (recipientObj instanceof String s && !s.isBlank()) {
            try { recipient = UUID.fromString(s); } catch (Exception ignored) {}
        }
        if (recipient == null) {
            log.warn("[workflow {} node {}] NOTIFICATION 无可用收件人,跳过",
                    ctx.instance().getId(), ctx.node().get("id"));
            return NodeOutcome.CONTINUE;
        }

        // 1) InApp 站内信
        MessageEntity msg = new MessageEntity();
        msg.setId(UUID.randomUUID());
        msg.setRecipient(recipient);
        msg.setType("workflow");
        msg.setTitle(title);
        msg.setBody(body);
        msg.setRelatedId(ctx.instance().getId().toString());
        msg.setCreatedAt(Instant.now());
        msg.setTenantId(ctx.instance().getTenantId());
        messageRepository.save(msg);

        // 2) 多渠道通知(event 格式 workflow.notification,与渠道配置 events 字段匹配)
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("title", title);
            payload.put("body", body);
            payload.put("data", Map.of(
                    "instance_id", ctx.instance().getId().toString(),
                    "node_id", String.valueOf(ctx.node().get("id")),
                    "workflow_id", String.valueOf(ctx.instance().getWorkflowId())
            ));
            notificationService.fire(ctx.instance().getTenantId(), "workflow.notification",
                    recipient.toString(), payload);
        } catch (Exception e) {
            log.warn("notify fire failed: {}", e.getMessage());
        }

        return NodeOutcome.CONTINUE;
    }
}
