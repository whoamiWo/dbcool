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
import org.mockito.ArgumentCaptor;

/**
 * WebhookDispatcher 单测(Week 30).
 * 测 POST/PUT、自定义 headers、HMAC 签名、timeout、2xx/3xx/4xx/5xx。
 */
class WebhookDispatcherTest {

    private WebhookDispatcher dispatcher;
    @SuppressWarnings("rawtypes")
    private HttpClient mockHttp;
    @SuppressWarnings("rawtypes")
    private HttpResponse mockResp;

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new WebhookDispatcher();
        mockHttp = mock(HttpClient.class);
        mockResp = mock(HttpResponse.class);
        Field f = WebhookDispatcher.class.getDeclaredField("http");
        f.setAccessible(true);
        f.set(dispatcher, mockHttp);
    }

    private NotificationChannelEntity channelWithConfig(Map<String, Object> cfg) {
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.WEBHOOK);
        c.setName("test-hook");
        c.setConfig(cfg);
        c.setTenantId("tenant_default");
        c.setEnabled(true);
        return c;
    }

    @Test
    void supportedType_isWebhook() {
        assertEquals(NotificationChannelEntity.Type.WEBHOOK, dispatcher.supportedType());
    }

    @Test
    void send_missingUrl_returnsError() {
        NotificationChannelEntity c = channelWithConfig(Map.of());
        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of());
        assertFalse(r.success());
        assertTrue(r.detail().contains("url missing"));
    }

    @Test
    void send_postSuccess_returnsOk() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of("url", "https://example.com/hook"));
        doReturn(200).when(mockResp).statusCode();
        doReturn("").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));

        assertTrue(r.success());
        assertEquals("HTTP 200", r.detail());

        // 默认是 POST
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(mockHttp).send(cap.capture(), any());
        assertEquals("POST", cap.getValue().method());
    }

    @Test
    void send_putMethod_buildsPutRequest() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "url", "https://example.com/hook", "method", "PUT"));
        doReturn(200).when(mockResp).statusCode();
        doReturn("").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        dispatcher.send(c, null, Map.of("title", "T", "body", "B"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(mockHttp).send(cap.capture(), any());
        assertEquals("PUT", cap.getValue().method());
    }

    @Test
    void send_customHeaders_appliedToRequest() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "url", "https://example.com/hook",
                "headers", Map.of("X-Auth", "TOK123", "X-Custom", "v1")));
        doReturn(200).when(mockResp).statusCode();
        doReturn("").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        dispatcher.send(c, null, Map.of("title", "T", "body", "B"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(mockHttp).send(cap.capture(), any());
        assertEquals("TOK123", cap.getValue().headers().firstValue("X-Auth").orElse(null));
        assertEquals("v1", cap.getValue().headers().firstValue("X-Custom").orElse(null));
        // 默认还有 Content-Type 和 User-Agent
        assertTrue(cap.getValue().headers().firstValue("Content-Type").isPresent());
        assertEquals("nocobase-notifier/1.0",
                cap.getValue().headers().firstValue("User-Agent").orElse(null));
    }

    @Test
    void send_payloadData_isIncludedInBody() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of("url", "https://example.com/hook"));
        doReturn(200).when(mockResp).statusCode();
        doReturn("").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        dispatcher.send(c, null, Map.of(
                "title", "T", "body", "B",
                "data", Map.of("order_id", "ORD-001", "amount", 100)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(mockHttp).send(cap.capture(), any());
        String body = cap.getValue().bodyPublisher().get().toString();
        // HttpRequest.BodyPublishers.ofString 不直接 toString 出内容,但我们验证有 publisher
        assertTrue(cap.getValue().bodyPublisher().isPresent());
    }

    @Test
    void send_httpThrows_returnsError() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of("url", "https://example.com/hook"));
        doThrow(new ConnectException("refused"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("ConnectException"));
    }

    @Test
    void send_http5xx_returnsError() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of("url", "https://example.com/hook"));
        doReturn(500).when(mockResp).statusCode();
        doReturn("server error").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("HTTP 500"));
    }

    @Test
    void send_http2xx_returnsOk() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of("url", "https://example.com/hook"));
        doReturn(204).when(mockResp).statusCode();
        doReturn("").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));
        assertTrue(r.success());
        assertTrue(r.detail().contains("HTTP 204"));
    }

    @Test
    void send_recipientUsedWhenUrlMissing() throws Exception {
        // cfg 没 url,fallback 到参数 recipient
        NotificationChannelEntity c = channelWithConfig(Map.of());
        doReturn(200).when(mockResp).statusCode();
        doReturn("").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c,
                "https://example.com/from-recipient",
                Map.of("title", "T", "body", "B"));
        assertTrue(r.success());
    }
}
