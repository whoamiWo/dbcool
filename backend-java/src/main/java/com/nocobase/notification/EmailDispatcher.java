package com.nocobase.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Email 渠道 dispatcher.
 * 配置示例:
 *   config: {
 *     "smtp_host": "smtp.example.com",
 *     "smtp_port": 587,
 *     "username": "noreply@example.com",
 *     "password": "xxx",
 *     "from": "noreply@example.com",
 *     "from_name": "nocobase"
 *   }
 * 如果未配置 SMTP,则记 INFO log(开发环境).
 */
@Component
public class EmailDispatcher implements NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    @Override
    public NotificationChannelEntity.Type supportedType() {
        return NotificationChannelEntity.Type.EMAIL;
    }

    @Override
    public SendResult send(NotificationChannelEntity channel, String recipient, Map<String, Object> payload) {
        if (recipient == null || recipient.isBlank()) {
            return SendResult.error("recipient email is blank");
        }
        Map<String, Object> cfg = channel.getConfig();
        String host = (String) cfg.get("smtp_host");
        String subject = String.valueOf(payload.getOrDefault("title", "(no title)"));
        String body = String.valueOf(payload.getOrDefault("body", ""));

        if (host == null || host.isBlank()) {
            // 模拟模式 — 仅记 log
            log.info("[EMAIL-MOCK to={}] subject={} body={}", recipient, subject, body);
            return SendResult.ok("mock-sent (no smtp_host configured): " + recipient);
        }

        // 真实发送留扩展点:JavaMailSender(需 spring-boot-starter-mail 依赖)
        // 此处保留 hook, 不强行引入依赖以保持镜像轻量
        log.info("[EMAIL to={} via {}] subject={}", recipient, host, subject);
        return SendResult.ok("logged-send: " + recipient + " via " + host);
    }
}
