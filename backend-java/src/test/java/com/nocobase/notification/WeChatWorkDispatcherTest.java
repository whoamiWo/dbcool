package com.nocobase.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WeChatWorkDispatcher 单测(Week 30).
 */
class WeChatWorkDispatcherTest {

    private WeChatWorkDispatcher dispatcher;
    @SuppressWarnings("rawtypes")
    private HttpClient mockHttp;
    @SuppressWarnings("rawtypes")
    private HttpResponse mockResp;

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new WeChatWorkDispatcher();
        mockHttp = mock(HttpClient.class);
        mockResp = mock(HttpResponse.class);
        Field f = WeChatWorkDispatcher.class.getDeclaredField("http");
        f.setAccessible(true);
        f.set(dispatcher, mockHttp);
    }

    private NotificationChannelEntity channelWithConfig(Map<String, Object> cfg) {
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WECHAT_WORK);
        c.setName("test");
        c.setConfig(cfg);
        c.setTenantId("tenant_default");
        c.setEnabled(true);
        return c;
    }

    @Test
    void supportedType_isWeChatWork() {
        assertEquals(NotificationChannelEntity.Type.WECHAT_WORK, dispatcher.supportedType());
    }

    @Test
    void send_missingUrl_returnsError() {
        NotificationChannelEntity c = channelWithConfig(Map.of());
        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of());
        assertFalse(r.success());
        assertTrue(r.detail().contains("missing"));
    }

    @Test
    void send_usesRecipientWhenWebhookUrlMissing() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of());
        doReturn(200).when(mockResp).statusCode();
        doReturn("{\"errcode\":0}").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c,
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=FALLBACK",
                Map.of("title", "T", "body", "B"));
        assertTrue(r.success());
    }

    @Test
    void send_httpSuccess_returnsOk() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ABC"));
        doReturn(200).when(mockResp).statusCode();
        doReturn("ok").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "Hi", "body", "world"));
        assertTrue(r.success());
        assertTrue(r.detail().contains("HTTP 200"));
    }

    @Test
    void send_httpNon200_returnsError() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ABC"));
        doReturn(403).when(mockResp).statusCode();
        doReturn("denied").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("HTTP 403"));
    }

    @Test
    void send_httpThrows_returnsError() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ABC"));
        doThrow(new ConnectException("refused"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("ConnectException"));
    }

    @Test
    void send_truncatesLongErrorBody() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=ABC"));
        String longBody = "y".repeat(300);
        doReturn(500).when(mockResp).statusCode();
        doReturn(longBody).when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("..."));
    }
}
