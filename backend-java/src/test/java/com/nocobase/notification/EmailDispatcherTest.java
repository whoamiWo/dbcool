package com.nocobase.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * EmailDispatcher 单元测试(Week 22 抬红线).
 *
 * <p>无外部依赖,纯逻辑测试。
 */
class EmailDispatcherTest {

    private final EmailDispatcher dispatcher = new EmailDispatcher();

    @Test
    void supportedType_isEMAIL() {
        assertThat(dispatcher.supportedType()).isEqualTo(NotificationChannelEntity.Type.EMAIL);
    }

    @Test
    void send_nullRecipient_returnsError() {
        var ch = makeChannel(Map.of());
        var r = dispatcher.send(ch, null, Map.of("title", "hi"));
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("recipient");
    }

    @Test
    void send_blankRecipient_returnsError() {
        var ch = makeChannel(Map.of());
        var r = dispatcher.send(ch, "  ", Map.of("title", "hi"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void send_noSmtpHost_returnsError() {
        // PHASE96 T2: 未配 smtp_host 必须显式失败，禁止 ok("mock-sent …") 假成功
        var ch = makeChannel(Map.of());  // 没 smtp_host
        var r = dispatcher.send(ch, "user@example.com",
                Map.of("title", "Hello", "body", "World"));
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("smtp_host");
    }

    @Test
    void send_smtpHostWithoutRealSend_returnsError() {
        // PHASE96 T2: 配了 host 但 real_send 未开启，同样必须显式失败
        // （此前返回 ok("logged-send …") —— 一封不发却报成功，比上一处更隐蔽）
        var ch = makeChannel(Map.of(
                "smtp_host", "smtp.example.com",
                "username", "noreply",
                "password", "secret",
                "from", "noreply@example.com"));
        var r = dispatcher.send(ch, "user@example.com",
                Map.of("title", "Subj", "body", "Body"));
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("real_send");
    }

    @Test
    void send_realSendFalse_returnsError() {
        // real_send 显式为 false 与未配置同罪：不授权真实投递就不得报成功
        var ch = makeChannel(Map.of(
                "smtp_host", "smtp.example.com",
                "real_send", false));
        var r = dispatcher.send(ch, "user@example.com", Map.of("title", "T"));
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("real_send");
    }

    @Test
    void send_missingTitle_defaultsToNoTitle() {
        var ch = makeChannel(Map.of());
        var r = dispatcher.send(ch, "u@e.com", Map.of("body", "only body"));
        // 不抛异常；未配 SMTP 时返回显式失败（PHASE96 T2），title 缺省仍走默认值不 NPE
        assertThat(r.success()).isFalse();
    }

    @Test
    void send_missingBody_emptyString() {
        var ch = makeChannel(Map.of());
        var r = dispatcher.send(ch, "u@e.com", Map.of("title", "OnlyTitle"));
        assertThat(r.success()).isFalse();
    }

    @Test
    void send_realSendEnabled_attemptsRealDelivery() {
        // Week 41 复核 D5.1:real_send=true 时走真实投递路径。
        // 本地 2525 无 SMTP 服务 → 发送失败 → 返回 error(而不是静默 "logged-send")
        var ch = makeChannel(Map.of(
                "smtp_host", "127.0.0.1",
                "smtp_port", 2525,
                "username", "noreply",
                "password", "secret",
                "from", "noreply@example.com",
                "real_send", true));
        var r = dispatcher.send(ch, "user@example.com",
                Map.of("title", "Subj", "body", "Body"));
        assertThat(r.success()).isFalse();
        assertThat(r.detail()).contains("email send failed");
    }

    private NotificationChannelEntity makeChannel(Map<String, Object> config) {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setConfig(new HashMap<>(config));
        ch.setName("test_email");
        ch.setType(NotificationChannelEntity.Type.EMAIL);
        ch.setEnabled(true);
        return ch;
    }
}