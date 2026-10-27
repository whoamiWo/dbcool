package com.nocobase.notification;

import com.nocobase.integration.credential.TenantCredentialService;
import com.nocobase.notification.NotificationDispatcher.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * B6 微信订阅消息分发器单测。
 *
 * <p>核心回归点：修复前 {@code send()} 把入参 payload 原样当请求体发送，
 * {@code recipient}(openid) 只出现在日志里，从未写入 {@code touser}
 * → 微信缺少 touser 必然返回 errcode 非 0，消息实际发不出去（B6 假完成）。
 */
@ExtendWith(MockitoExtension.class)
class WeChatSubscribeDispatcherTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private TenantCredentialService credentialService;

    private WeChatSubscribeDispatcher dispatcher;

    private static final String TENANT = "t1";

    @BeforeEach
    void setUp() {
        dispatcher = new WeChatSubscribeDispatcher(restTemplate, credentialService);
    }

    private NotificationChannelEntity channel() {
        NotificationChannelEntity ch = new NotificationChannelEntity();
        ch.setTenantId(TENANT);
        return ch;
    }

    private void stubCredentials() {
        when(credentialService.get(eq(TENANT), eq("wechat"), eq("app-id")))
                .thenReturn(Optional.of("wx_appid"));
        when(credentialService.get(eq(TENANT), eq("wechat"), eq("app-secret")))
                .thenReturn(Optional.of("wx_secret"));
        // getAccessToken 内部走 restTemplate.getForObject(url, String.class)
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"access_token\":\"tok_123\"}");
    }

    /** touser 必须由 recipient 写入请求体（B6 核心修复）。 */
    @Test
    void send_writesTouserIntoBody() {
        stubCredentials();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.postForObject(anyString(), bodyCaptor.capture(), eq(String.class)))
                .thenReturn("{\"errcode\":0,\"errmsg\":\"ok\"}");

        Map<String, Object> payload = Map.of(
                "template_id", "TEMPLATE_1",
                "data", Map.of("thing1", Map.of("value", "内容")));

        SendResult r = dispatcher.send(channel(), "openid_abc", payload);

        assertTrue(r.success(), "发送应成功: " + r.detail());
        String sent = bodyCaptor.getValue();
        assertTrue(sent.contains("\"touser\":\"openid_abc\""),
                "请求体必须包含 touser=openid_abc（修复前缺失），实际: " + sent);
        assertTrue(sent.contains("\"template_id\":\"TEMPLATE_1\""),
                "payload 原有字段应保留: " + sent);
    }

    /** recipient 为 null 时不应抛异常，touser 写为空串。 */
    @Test
    void send_nullRecipient_noThrow() {
        stubCredentials();
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        when(restTemplate.postForObject(anyString(), bodyCaptor.capture(), eq(String.class)))
                .thenReturn("{\"errcode\":0}");

        SendResult r = dispatcher.send(channel(), null, Map.of("template_id", "T1"));

        assertTrue(r.success());
        assertTrue(bodyCaptor.getValue().contains("\"touser\":\"\""),
                "recipient 为 null 时 touser 应为空串");
    }

    /** errcode 非 0 必须判定失败（原有逻辑正确，保持不回归）。 */
    @Test
    void send_errcodeNonZero_fails() {
        stubCredentials();
        when(restTemplate.postForObject(anyString(), anyString(), eq(String.class)))
                .thenReturn("{\"errcode\":40003,\"errmsg\":\"invalid openid\"}");

        SendResult r = dispatcher.send(channel(), "bad_openid", Map.of("template_id", "T1"));

        assertFalse(r.success(), "errcode 非 0 应失败");
        assertTrue(r.detail().contains("40003"), "详情应含 errcode: " + r.detail());
    }

    /** 凭证缺失时直接失败，不走 HTTP。 */
    @Test
    void send_missingCredentials_fails() {
        when(credentialService.get(eq(TENANT), eq("wechat"), eq("app-id")))
                .thenReturn(Optional.empty());

        SendResult r = dispatcher.send(channel(), "openid_abc", Map.of());

        assertFalse(r.success());
        assertTrue(r.detail().contains("app-id") || r.detail().contains("凭证")
                || r.detail().contains("未配置"), "详情: " + r.detail());
    }

    /** 支持的渠道类型。 */
    @Test
    void supportedType_isWeChatSubscribe() {
        assertEquals(NotificationChannelEntity.Type.WECHAT_SUBSCRIBE, dispatcher.supportedType());
    }
}
