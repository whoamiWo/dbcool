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
 * FeishuPersonalDispatcher 单元测试。
 *
 * <p>通过 mock {@link WebClient} 的链式调用覆盖 send() 的全部分支。
 */
class FeishuPersonalDispatcherTest {

    /** 构造一个已接好 WebClient 链路的 dispatcher，返回给定响应体。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private FeishuPersonalDispatcher dispatcherReturning(Mono<Map> responseMono) {
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

        return new FeishuPersonalDispatcher(builder);
    }

    private NotificationChannelEntity channel(Map<String, Object> config) {
        NotificationChannelEntity c = new NotificationChannelEntity();
        c.setConfig(config);
        return c;
    }

    @Test
    void testSupportedType() {
        FeishuPersonalDispatcher dispatcher = dispatcherReturning(Mono.empty());
        assertEquals(NotificationChannelEntity.Type.FEISHU_PERSONAL, dispatcher.supportedType());
    }

    @Test
    void send_missingWebhookUrl_returnsError() {
        FeishuPersonalDispatcher dispatcher = dispatcherReturning(Mono.empty());
        NotificationDispatcher.SendResult result = dispatcher.send(channel(new HashMap<>()), "user1", Map.of("title", "test"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("missing webhookUrl"));
    }

    @Test
    void send_emptyWebhookUrl_returnsError() {
        FeishuPersonalDispatcher dispatcher = dispatcherReturning(Mono.empty());
        NotificationDispatcher.SendResult result = dispatcher.send(channel(Map.of("webhookUrl", "")), "user1", Map.of("title", "test"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("missing webhookUrl"));
    }

    @Test
    void send_textMessage_success() {
        FeishuPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("StatusCode", 0, "Message", "success")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-feishu")),
                "user1",
                Map.of("title", "hello", "content", "world"));
        assertTrue(result.success());
        assertEquals("feishu webhook sent", result.detail());
    }

    @Test
    void send_interactiveCardMessage_success() {
        FeishuPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("StatusCode", 0, "Message", "success")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-feishu", "msgType", "interactive")),
                "user1",
                Map.of("title", "card", "content", "body", "link", "http://x"));
        assertTrue(result.success());
    }

    @Test
    void send_emptyResponse_returnsError() {
        FeishuPersonalDispatcher dispatcher = dispatcherReturning(Mono.empty());
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-feishu")), "user1", Map.of("title", "test"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("empty response"));
    }

    @Test
    void send_nonZeroStatusCode_returnsError() {
        FeishuPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("StatusCode", 1, "Message", "bad param")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-feishu")), "user1", Map.of("title", "test"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("feishu error"));
        assertTrue(result.detail().contains("bad param"));
    }

    @Test
    void send_nullStatusCode_returnsError() {
        FeishuPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.just(Map.of("Message", "no status")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-feishu")), "user1", Map.of("title", "test"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("feishu error"));
    }

    @Test
    void send_exception_returnsError() {
        FeishuPersonalDispatcher dispatcher =
                dispatcherReturning(Mono.error(new RuntimeException("network error")));
        NotificationDispatcher.SendResult result = dispatcher.send(
                channel(Map.of("webhookUrl", "http://test-feishu")), "user1", Map.of("title", "test"));
        assertFalse(result.success());
        assertTrue(result.detail().contains("RuntimeException"));
    }
}
