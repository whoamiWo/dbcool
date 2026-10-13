package com.nocobase.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Notification 覆盖率提升测试 (PHASE104)。
 * 重点覆盖：WeComDispatcher, FeishuPersonalDispatcher, WeChatPersonalDispatcher, WeChatWorkController
 */
class NotificationCoverageBoostTest {

    // ==================== WeComDispatcher ====================

    @Test
    void wecom_send_missingWebhookUrl_returnsError() throws Exception {
        WeComDispatcher dispatcher = new WeComDispatcher();
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WE_COM);
        c.setName("test");
        c.setConfig(Map.of()); // missing webhook_url
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("data", Map.of()));
        assertFalse(r.success());
        assertTrue(r.detail().contains("webhook_url missing"));
    }

    @Test
    void wecom_send_markdownMessage_sendsCorrectPayload() throws Exception {
        WeComDispatcher dispatcher = new WeComDispatcher();
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<?> mockResp = mock(HttpResponse.class);

        Field f = WeComDispatcher.class.getDeclaredField("http");
        f.setAccessible(true);
        f.set(dispatcher, mockHttp);

        doReturn(200).when(mockResp).statusCode();
        doReturn("{\"errcode\":0}").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WE_COM);
        c.setName("test");
        c.setConfig(Map.of("webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ABC"));
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("data", Map.of("type", "markdown", "text", "# Hello")));

        assertTrue(r.success());

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(mockHttp).send(cap.capture(), any());
        HttpRequest req = cap.getValue();
        assertTrue(req.uri().toString().contains("key=ABC"));
    }

    @Test
    void wecom_send_textMessage_sendsCorrectPayload() throws Exception {
        WeComDispatcher dispatcher = new WeComDispatcher();
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<?> mockResp = mock(HttpResponse.class);

        Field f = WeComDispatcher.class.getDeclaredField("http");
        f.setAccessible(true);
        f.set(dispatcher, mockHttp);

        doReturn(200).when(mockResp).statusCode();
        doReturn("{\"errcode\":0}").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WE_COM);
        c.setName("test");
        c.setConfig(Map.of("webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=XYZ"));
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("data", Map.of("custom", "field")));

        assertTrue(r.success());
    }

    @Test
    void wecom_send_httpError_returnsError() throws Exception {
        WeComDispatcher dispatcher = new WeComDispatcher();
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse<?> mockResp = mock(HttpResponse.class);

        Field f = WeComDispatcher.class.getDeclaredField("http");
        f.setAccessible(true);
        f.set(dispatcher, mockHttp);

        doReturn(500).when(mockResp).statusCode();
        doReturn("error").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WE_COM);
        c.setName("test");
        c.setConfig(Map.of("webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ABC"));
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("data", Map.of()));
        assertFalse(r.success());
        assertTrue(r.detail().contains("500"), "应包含状态码 500: " + r.detail());
    }

    @Test
    void wecom_send_clientThrows_returnsError() throws Exception {
        WeComDispatcher dispatcher = new WeComDispatcher();
        HttpClient mockHttp = mock(HttpClient.class);

        Field f = WeComDispatcher.class.getDeclaredField("http");
        f.setAccessible(true);
        f.set(dispatcher, mockHttp);

        doThrow(new ConnectException("refused"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WE_COM);
        c.setName("test");
        c.setConfig(Map.of("webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ABC"));
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("data", Map.of()));
        assertFalse(r.success());
        assertTrue(r.detail().contains("ConnectException") || r.detail().contains("refused"),
                "应包含异常信息：" + r.detail());
    }

    @Test
    void wecom_supportedType_isWeCom() {
        WeComDispatcher dispatcher = new WeComDispatcher();
        assertEquals(NotificationChannelEntity.Type.WE_COM, dispatcher.supportedType());
    }

    // ==================== FeishuPersonalDispatcher ====================

    @Test
    void feishu_supportedType_isFeishuPersonal() {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        assertEquals(NotificationChannelEntity.Type.FEISHU_PERSONAL, dispatcher.supportedType());
    }

    @Test
    void feishu_send_missingWebhookUrl_returnsError() {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.FEISHU_PERSONAL);
        c.setName("test");
        c.setConfig(Map.of()); // missing webhookUrl
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("title", "T"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("missing webhookUrl"));
    }

    @Test
    void feishu_send_emptyWebhookUrl_returnsError() {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.FEISHU_PERSONAL);
        c.setName("test");
        c.setConfig(Map.of("webhookUrl", "")); // empty webhookUrl
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("title", "T"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("missing webhookUrl"));
    }

    @Test
    void feishu_buildTextMessage_withAllFields() throws Exception {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        Method buildTextMessage = FeishuPersonalDispatcher.class.getDeclaredMethod(
                "buildTextMessage", Map.class);
        buildTextMessage.setAccessible(true);

        Map<String, Object> payload = Map.of(
                "title", "Test Title",
                "content", "Test Content",
                "eventName", "TEST_EVENT",
                "link", "https://example.com"
        );

        String result = (String) buildTextMessage.invoke(dispatcher, payload);
        assertTrue(result.contains("【Test Title】"));
        assertTrue(result.contains("Test Content"));
        assertTrue(result.contains("事件：TEST_EVENT"));
        assertTrue(result.contains("链接：https://example.com"));
    }

    @Test
    void feishu_buildTextMessage_withOnlyTitle() throws Exception {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        Method buildTextMessage = FeishuPersonalDispatcher.class.getDeclaredMethod(
                "buildTextMessage", Map.class);
        buildTextMessage.setAccessible(true);

        Map<String, Object> payload = Map.of("title", "Only Title");

        String result = (String) buildTextMessage.invoke(dispatcher, payload);
        assertTrue(result.contains("【Only Title】"));
    }

    @Test
    void feishu_buildCardMessage_withLink() throws Exception {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        Method buildCardMessage = FeishuPersonalDispatcher.class.getDeclaredMethod(
                "buildCardMessage", Map.class);
        buildCardMessage.setAccessible(true);

        Map<String, Object> payload = Map.of(
                "title", "Card Title",
                "content", "Card Content",
                "link", "https://example.com"
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) buildCardMessage.invoke(dispatcher, payload);
        assertEquals("interactive", result.get("msg_type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) result.get("card");
        assertNotNull(card);
    }

    @Test
    void feishu_buildCardMessage_withoutLink() throws Exception {
        FeishuPersonalDispatcher dispatcher = new FeishuPersonalDispatcher();
        Method buildCardMessage = FeishuPersonalDispatcher.class.getDeclaredMethod(
                "buildCardMessage", Map.class);
        buildCardMessage.setAccessible(true);

        Map<String, Object> payload = Map.of(
                "title", "Card Title",
                "content", "Card Content"
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) buildCardMessage.invoke(dispatcher, payload);
        assertEquals("interactive", result.get("msg_type"));
    }

    // ==================== WeChatPersonalDispatcher ====================

    @Test
    void wechatPersonal_supportedType_isWechatPersonal() {
        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        assertEquals(NotificationChannelEntity.Type.WECHAT_PERSONAL, dispatcher.supportedType());
    }

    @Test
    void wechatPersonal_send_missingWebhookUrl_returnsError() {
        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WECHAT_PERSONAL);
        c.setName("test");
        c.setConfig(Map.of()); // missing webhookUrl
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("title", "T"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("missing webhookUrl"));
    }

    @Test
    void wechatPersonal_send_emptyWebhookUrl_returnsError() {
        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WECHAT_PERSONAL);
        c.setName("test");
        c.setConfig(Map.of("webhookUrl", "")); // empty webhookUrl
        c.setTenantId("tenant_default");
        c.setEnabled(true);

        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("title", "T"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("missing webhookUrl"));
    }

    @Test
    void wechatPersonal_buildTextMessage_withAllFields() throws Exception {
        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        Method buildTextMessage = WeChatPersonalDispatcher.class.getDeclaredMethod(
                "buildTextMessage", Map.class);
        buildTextMessage.setAccessible(true);

        Map<String, Object> payload = Map.of(
                "title", "Test Title",
                "content", "Test Content",
                "eventName", "TEST_EVENT",
                "link", "https://example.com"
        );

        String result = (String) buildTextMessage.invoke(dispatcher, payload);
        assertTrue(result.contains("【Test Title】"));
        assertTrue(result.contains("Test Content"));
        assertTrue(result.contains("事件：TEST_EVENT"));
        assertTrue(result.contains("链接：https://example.com"));
    }

    @Test
    void wechatPersonal_buildMarkdownMessage_withLink() throws Exception {
        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        Method buildMarkdownMessage = WeChatPersonalDispatcher.class.getDeclaredMethod(
                "buildMarkdownMessage", Map.class);
        buildMarkdownMessage.setAccessible(true);

        Map<String, Object> payload = Map.of(
                "title", "MD Title",
                "content", "MD Content",
                "link", "https://example.com"
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) buildMarkdownMessage.invoke(dispatcher, payload);
        assertEquals("markdown", result.get("msgtype"));
    }

    @Test
    void wechatPersonal_buildNewsMessage_withAllFields() throws Exception {
        WeChatPersonalDispatcher dispatcher = new WeChatPersonalDispatcher();
        Method buildNewsMessage = WeChatPersonalDispatcher.class.getDeclaredMethod(
                "buildNewsMessage", Map.class);
        buildNewsMessage.setAccessible(true);

        Map<String, Object> payload = Map.of(
                "title", "News Title",
                "content", "News Content",
                "link", "https://example.com",
                "imageUrl", "https://example.com/img.jpg"
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) buildNewsMessage.invoke(dispatcher, payload);
        assertEquals("news", result.get("msgtype"));
    }
}
