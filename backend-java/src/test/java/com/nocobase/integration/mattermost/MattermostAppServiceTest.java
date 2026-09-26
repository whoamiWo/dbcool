package com.nocobase.integration.mattermost;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mattermost 应用服务测试 — 覆盖 Webhook Token 校验与配置守卫。
 */
class MattermostAppServiceTest {

    private MattermostAppService service;

    @BeforeEach
    void setUp() {
        service = new MattermostAppService();
        setField(service, "siteUrl", "https://mattermost.example.com");
        setField(service, "botToken", "testBotToken");
        setField(service, "webhookToken", "testWebhookToken");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = MattermostAppService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void isConfigured_returnsTrueWhenConfigured() {
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void getIncomingWebhookUrl_returnsConfiguredUrl() {
        setField(service, "incomingWebhookUrl", "https://mattermost.example.com/hooks/test");
        assertThat(service.getIncomingWebhookUrl()).isEqualTo("https://mattermost.example.com/hooks/test");
    }

    @Test
    void verifyWebhookToken_validToken_accepted() {
        assertThat(service.verifyWebhookToken("testWebhookToken")).isTrue();
    }

    @Test
    void verifyWebhookToken_invalidToken_rejected() {
        assertThat(service.verifyWebhookToken("bogus")).isFalse();
    }

    @Test
    void verifyWebhookToken_nullToken_rejected() {
        assertThat(service.verifyWebhookToken(null)).isFalse();
    }
}