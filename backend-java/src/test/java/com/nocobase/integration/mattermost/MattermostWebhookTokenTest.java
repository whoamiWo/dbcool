package com.nocobase.integration.mattermost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nocobase.integration.common.InboundMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T3：Mattermost 入站 token 校验必须 fail-close。
 *
 * <p>修复前：未配置 token 时 {@code return true}（未配置 = 任何人都能打进来）。
 * 修复后：{@code requireToken=true} 且未配置 → false + WARN；
 * 仅当**显式**把 require-token 设为 false 时才放行。
 */
class MattermostWebhookTokenTest {

    private MattermostAppService service;

    @BeforeEach
    void setUp() {
        // 本用例的被测对象是 token 校验逻辑，入站落库服务作为参数隔离
        service = new MattermostAppService(mock(InboundMessageService.class));
    }

    @Test
    void verifyWebhookToken_notConfiguredWithRequireTokenTrue_rejects() {
        ReflectionTestUtils.setField(service, "requireToken", true);
        ReflectionTestUtils.setField(service, "webhookToken", "");

        assertThat(service.verifyWebhookToken("any")).isFalse();
        assertThat(service.verifyWebhookToken(null)).isFalse();
    }

    @Test
    void verifyWebhookToken_configured_correctTokenAccepted() {
        ReflectionTestUtils.setField(service, "requireToken", true);
        ReflectionTestUtils.setField(service, "webhookToken", "secret-token");

        assertThat(service.verifyWebhookToken("secret-token")).isTrue();
    }

    @Test
    void verifyWebhookToken_configured_wrongTokenRejected() {
        ReflectionTestUtils.setField(service, "requireToken", true);
        ReflectionTestUtils.setField(service, "webhookToken", "secret-token");

        assertThat(service.verifyWebhookToken("wrong")).isFalse();
        assertThat(service.verifyWebhookToken(null)).isFalse();
    }

    @Test
    void verifyWebhookToken_requireTokenExplicitlyDisabled_allows() {
        ReflectionTestUtils.setField(service, "requireToken", false);
        ReflectionTestUtils.setField(service, "webhookToken", "");

        assertThat(service.verifyWebhookToken("anything")).isTrue();
    }
}
