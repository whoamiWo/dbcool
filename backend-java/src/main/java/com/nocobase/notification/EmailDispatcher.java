package com.nocobase.notification;

import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Email 渠道 dispatcher。
 *
 * <p>配置示例:
 * <pre>{@code
 * config: {
 *   "smtp_host": "smtp.example.com",
 *   "smtp_port": 587,
 *   "username": "noreply@example.com",
 *   "password": "xxx",
 *   "from": "noreply@example.com",
 *   "from_name": "nocobase",
 *   "real_send": true          // 开启真实投递
 * }
 * }</pre>
 *
 * <p>Week 41 复核 D5.1:此前<strong>即使配了 smtp_host 也只打一行日志</strong>,
 * 真实发送一直是 TODO。现已补完 —— 按 channel 配置动态构建 {@code JavaMailSender},
 * 不依赖全局 {@code spring.mail.*} 配置(因为 SMTP 是按渠道动态配置的)。
 *
 * <p><strong>安全开关</strong>:真实投递需显式配置 {@code real_send = true}。
 * 未开启时保持既有 {@code logged-send} 行为 —— 既向后兼容,
 * 也避免配置失误导致误发邮件。
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
        Map<String, Object> cfg = channel.getConfig() == null ? Map.of() : channel.getConfig();
        String host = (String) cfg.get("smtp_host");
        String subject = String.valueOf(payload.getOrDefault("title", "(no title)"));
        String body = String.valueOf(payload.getOrDefault("body", ""));

        if (host == null || host.isBlank()) {
            // 未配置 SMTP:模拟模式(保持既有行为)
            log.info("[EMAIL-MOCK to={}] subject={} body={}", recipient, subject, body);
            return SendResult.ok("mock-sent (no smtp_host configured): " + recipient);
        }

        // 真实投递需显式开启,未开启时保持既有 logged-send 行为
        if (!isRealSend(cfg)) {
            log.info("[EMAIL to={} via {}] subject={} (real_send 未开启,仅记录)",
                    recipient, host, subject);
            return SendResult.ok("logged-send: " + recipient + " via " + host);
        }

        try {
            JavaMailSenderImpl sender = buildSender(cfg, host);
            sender.send(mime -> {
                MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
                helper.setTo(recipient);
                helper.setSubject(subject);
                helper.setText(body, false);
                String from = (String) cfg.get("from");
                if (from != null && !from.isBlank()) {
                    helper.setFrom(from, String.valueOf(cfg.getOrDefault("from_name", from)));
                }
            });
            log.info("[EMAIL sent to={} via {}] subject={}", recipient, host, subject);
            return SendResult.ok("sent: " + recipient + " via " + host);
        } catch (Exception e) {
            log.warn("[EMAIL 发送失败 to={} via {}]: {}", recipient, host, e.getMessage());
            return SendResult.error("email send failed: " + e.getMessage());
        }
    }

    private static boolean isRealSend(Map<String, Object> cfg) {
        Object v = cfg.get("real_send");
        if (v instanceof Boolean b) return b;
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }

    /** 按 channel 配置动态构建 sender(SMTP 是每渠道独立的,不能用全局单例)。 */
    private static JavaMailSenderImpl buildSender(Map<String, Object> cfg, String host) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(cfg.get("smtp_port") instanceof Number n ? n.intValue() : 587);
        Object user = cfg.get("username");
        Object pass = cfg.get("password");
        if (user != null) sender.setUsername(String.valueOf(user));
        if (pass != null) sender.setPassword(String.valueOf(pass));
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(user != null && pass != null));
        props.put("mail.smtp.starttls.enable", "true");
        // 超时保护:避免 SMTP 不可达时长时间阻塞调用方(尤其测试环境)
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }
}
