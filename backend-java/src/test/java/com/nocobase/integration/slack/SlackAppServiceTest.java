package com.nocobase.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slack 应用服务测试 — 覆盖配置守卫与签名密钥读取。
 */
class SlackAppServiceTest {

    private SlackAppService service;

    @BeforeEach
    void setUp() {
        service = new SlackAppService();
        setField(service, "clientId", "testClientId");
        setField(service, "clientSecret", "testClientSecret");
        setField(service, "redirectUri", "https://example.com/callback");
        setField(service, "signingSecret", "testSigningSecret");
        setField(service, "botToken", "xoxb-test-token");
        setField(service, "teamId", "T12345");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = SlackAppService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void isConfigured_returnsTrueWhenCredentialsSet() {
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void getSigningSecret_returnsConfiguredSecret() {
        assertThat(service.getSigningSecret()).isEqualTo("testSigningSecret");
    }

    @Test
    void getBotToken_returnsConfiguredToken() {
        assertThat(service.getBotToken()).isEqualTo("xoxb-test-token");
    }

    @Test
    void getTeamId_returnsConfiguredTeam() {
        assertThat(service.getTeamId()).isEqualTo("T12345");
    }

    @Test
    void isConfigured_missingClientId_returnsFalse() {
        setField(service, "clientId", "");
        assertThat(service.isConfigured()).isFalse();
    }
}