package com.nocobase.notification;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WeChatPersonalDispatcher 单元测试。
 *
 * <p>通过 mock {@link WebClient} 的链式调用覆盖 send() 的全部分支
 * (text/markdown/news 三种 msgType + 成功/空响应/非 0 errcode/异常)。
 */
class WeChatPersonalDispatcherTest {

    @SuppressWarnings({"unchecked", "rawtypes"})
    private WeChatPersonalDispatcher dispatcherReturning(Mono<Map> responseMono) {
        WebClient.Builder builder = mock(WebClient.Builder.class);
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec postSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec bodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec respSpec = mock(WebClient.ResponseSpec.class);

        when(builder.build()).thenReturn(webClient);
        when(webClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        when(bodySpec.bodyValue(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(respSpec);
        doReturn(responseMono).when(respSpec).bodyToMono(Map.class);

        return new WeChatPersonalDispatcher(builder);
    }

    private NotificationChannelEntity channel(Map<String, Object> config) {
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setConfig(config);
        c.setTenantId("tenant_default");
        return c;
    }

    @Test
    void testSupportedType() {
        WeChatPersonalDispatcher dispatcher = dispatcherReturning(Mono.empty());
        assertEquals(NotificationChannelEntity.Type.WECHAT_PERSONAL, dispatcher.supportedType());
    }

    @Test
    void send_missingWebhookUrl_returnsError() {
        WeChatPersonalDispatcher dispatcher = dispatcherReturning(Mono.empty());
        NotificationDispatcher.SendResult result =
                dispatcher.send(channel(new HashMap<>()), "user1", Map.of("title", "test"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("missing webhookUrl"));
    }

    @Test
    void send_textMessage_success() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("errcode", 0, "errmsg", "ok")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat")),
                "user1",
                Map.of("title", "t", "content", "c", "eventName", "e", "link", "http://x"));
        assertTrue(result.success());
        assertEquals("wechat personal webhook sent", result.detail());
    }

    @Test
    void send_markdownMessage_success() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("errcode", 0, "errmsg", "ok")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat", "msgType", "markdown")),
                "user1",
                Map.of("title", "t", "content", "c", "link", "http://x"));
        assertTrue(result.success());
    }

    @Test
    void send_markdownNoLink_success() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("errcode", 0, "errmsg", "ok")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat", "msgType", "markdown")),
                "user1",
                Map.of("content", "no link"));
        assertTrue(result.success());
    }

    @Test
    void send_newsMessage_success() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("errcode", 0, "errmsg", "ok")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat", "msgType", "news")),
                "user1",
                Map.of("title", "t", "content", "c", "link", "http://x", "imageUrl", "http://img"));
        assertTrue(result.success());
    }

    @Test
    void send_newsMessage_nullLinkAndImage_fallbackDefaults() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("errcode", 0, "errmsg", "ok")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat", "msgType", "news")),
                "user1",
                Map.of("content", "c"));
        assertTrue(result.success());
    }

    @Test
    void send_emptyResponse_returnsError() {
        WeChatPersonalDispatcher dispatcher = dispatcherReturning(Mono.empty());
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat")), "user1", Map.of("title", "t"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("empty response"));
    }

    @Test
    void send_nonZeroErrcode_returnsError() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("errcode", 93000, "errmsg", "invalid url")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat")), "user1", Map.of("title", "t"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("wechat error"));
        assertTrue(result.detail().contains("invalid url"));
    }

    @Test
    void send_nullErrcode_returnsError() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("errmsg", "no errcode")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat")), "user1", Map.of("title", "t"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("wechat error"));
    }

    @Test
    void send_exception_returnsError() {
        WeChatPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.error(new RuntimeException("boom")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-wechat")), "user1", Map.of("title", "t"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("RuntimeException"));
    }
}
