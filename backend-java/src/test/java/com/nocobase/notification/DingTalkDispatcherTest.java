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
 * DingTalkDispatcher 单测(Week 30).
 * HttpClient 是 final 实例字段,用反射替换为 mock.
 */
class DingTalkDispatcherTest {

    private DingTalkDispatcher dispatcher;
    @SuppressWarnings("rawtypes")
    private HttpClient mockHttp;
    @SuppressWarnings("rawtypes")
    private HttpResponse mockResp;

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new DingTalkDispatcher();
        mockHttp = mock(HttpClient.class);
        mockResp = mock(HttpResponse.class);
        Field f = DingTalkDispatcher.class.getDeclaredField("http");
        f.setAccessible(true);
        f.set(dispatcher, mockHttp);
    }

    private NotificationChannelEntity channelWithConfig(Map<String, Object> cfg) {
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setId(UUID.randomUUID());
        c.setType(NotificationChannelEntity.Type.DINGTALK);
        c.setName("test");
        c.setConfig(cfg);
        c.setTenantId("tenant_default");
        c.setEnabled(true);
        return c;
    }

    @Test
    void supportedType_isDingTalk() {
        assertEquals(NotificationChannelEntity.Type.DINGTALK, dispatcher.supportedType());
    }

    @Test
    void send_missingWebhookUrl_returnsError() {
        NotificationChannelEntity c = channelWithConfig(Map.of());
        NotificationDispatcher.SendResult r = dispatcher.send(c, null, Map.of("title", "T", "body", "B"));
        assertFalse(r.success());
        assertTrue(r.detail().contains("missing"));
    }

    @Test
    void send_withoutSecret_succeedsOnHttp200() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=ABC"));
        doReturn(200).when(mockResp).statusCode();
        doReturn("{\"errcode\":0}").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "Hi", "body", "world"));

        assertTrue(r.success());
        assertTrue(r.detail().contains("HTTP 200"));
    }

    @Test
    void send_withoutSecret_httpError_returnsError() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=ABC"));
        doReturn(403).when(mockResp).statusCode();
        doReturn("forbidden").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "Hi", "body", "world"));

        assertFalse(r.success());
        assertTrue(r.detail().contains("HTTP 403"));
    }

    @Test
    void send_withSecret_appendsTimestampAndSign() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://oapi.dingtalk.com/robot/send",
                "secret", "SECxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"));
        doReturn(200).when(mockResp).statusCode();
        doReturn("ok").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "Hi", "body", "world"));

        assertTrue(r.success());
        // 验证 URL 包含 timestamp 和 sign
        @SuppressWarnings("unchecked")
        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(mockHttp).send(cap.capture(), any());
        String url = cap.getValue().uri().toString();
        assertTrue(url.contains("timestamp="), "URL 应包含 timestamp: " + url);
        assertTrue(url.contains("sign="), "URL 应包含 sign: " + url);
    }

    @Test
    void send_httpClientThrows_returnsError() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=ABC"));
        doThrow(new ConnectException("refused"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));

        assertFalse(r.success());
        assertTrue(r.detail().contains("ConnectException"));
    }

    @Test
    void send_recipientUsedWhenWebhookUrlMissing() throws Exception {
        // cfg 没 webhook_url,fallback 到参数 recipient
        NotificationChannelEntity c = channelWithConfig(Map.of());
        doReturn(200).when(mockResp).statusCode();
        doReturn("ok").when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c,
                "https://oapi.dingtalk.com/robot/send?access_token=FALLBACK",
                Map.of("title", "T", "body", "B"));

        assertTrue(r.success());
    }

    @Test
    void send_truncatesLongResponseBody() throws Exception {
        NotificationChannelEntity c = channelWithConfig(Map.of(
                "webhook_url", "https://oapi.dingtalk.com/robot/send?access_token=ABC"));
        String longBody = "x".repeat(300);
        doReturn(500).when(mockResp).statusCode();
        doReturn(longBody).when(mockResp).body();
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        NotificationDispatcher.SendResult r = dispatcher.send(c, null,
                Map.of("title", "T", "body", "B"));

        assertFalse(r.success());
        // 500 路径用 truncate(.., 200),超过 200 应有省略号
        assertTrue(r.detail().contains("..."), "long body 应被截断: " + r.detail());
    }
}
