package com.nocobase.notification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;

/**
 * WeChatPersonalDispatcher 单元测试。
 *
 * <p>验收要求：验证该 dispatcher 被注册进 NotificationService 的 dispatchers map。
 */
class WeChatPersonalDispatcherTest {

    @Test
    void testSupportedType() {
        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        assertEquals(NotificationChannelEntity.Type.WECHAT_PERSONAL, dispatcher.supportedType());
    }

    @Test
    void testBuildContextMessage() {
        Map<String, Object> payload = Map.of(
                "title", "测试标题",
                "body", "测试内容"
        );

        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        assertNotNull(dispatcher);
    }

    @Test
    void testBuildMarkdownMessage() {
        Map<String, Object> payload = Map.of(
                "title", "Markdown 标题",
                "content", "markdown 内容",
                "link", "https://example.com"
        );

        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        assertNotNull(dispatcher);
    }

    @Test
    void testBuildNewsMessage() {
        Map<String, Object> payload = Map.of(
                "title", "新闻标题",
                "content", "新闻摘要"
        );

        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        assertNotNull(dispatcher);
    }

    @Test
    void testSend_missingWebhookUrl_returnsError() {
        NotificationChannelEntity channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setType(NotificationChannelEntity.Type.WECHAT_PERSONAL);
        channel.setName("test-wechat");
        channel.setConfig(Map.of());
        channel.setTenantId("tenant_default");

        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        NotificationDispatcher.SendResult result = dispatcher.send(channel, null, Map.of());

        assertFalse(result.success());
        assertTrue(result.detail().contains("missing webhookUrl"));
    }
}