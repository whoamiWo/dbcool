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
    void send_noSmtpHost_returnsMockOk() {
        var ch = makeChannel(Map.of());  // 没 smtp_host
        var r = dispatcher.send(ch, "user@example.com",
                Map.of("title", "Hello", "body", "World"));
        assertThat(r.success()).isTrue();
        assertThat(r.detail()).contains("mock-sent").contains("user@example.com");
    }

    @Test
    void send_withSmtpHost_returnsLoggedOk() {
        var ch = makeChannel(Map.of(
                "smtp_host", "smtp.example.com",
                "username", "noreply",
                "password", "secret",
                "from", "noreply@example.com"));
        var r = dispatcher.send(ch, "user@example.com",
                Map.of("title", "Subj", "body", "Body"));
        assertThat(r.success()).isTrue();
        assertThat(r.detail()).contains("logged-send").contains("smtp.example.com");
    }

    @Test
    void send_missingTitle_defaultsToNoTitle() {
        var ch = makeChannel(Map.of());
        var r = dispatcher.send(ch, "u@e.com", Map.of("body", "only body"));
        // 不抛异常,使用默认 title
        assertThat(r.success()).isTrue();
    }

    @Test
    void send_missingBody_emptyString() {
        var ch = makeChannel(Map.of());
        var r = dispatcher.send(ch, "u@e.com", Map.of("title", "OnlyTitle"));
        assertThat(r.success()).isTrue();
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