package com.nocobase.integration.common;

import com.nocobase.integration.credential.TenantCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE B B1: WeChatJsSignatureProvider 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>provider() 标识</li>
 *   <li>凭证缺失时抛 IllegalStateException（不静默降级）</li>
 *   <li>ticket 命中缓存时不调用 RestTemplate</li>
 *   <li>ticket 未命中缓存时拉取 access_token + jsapi_ticket 并写入缓存</li>
 *   <li>签名 SHA1 校验：用官方样例验证正确性</li>
 * </ul>
 */
class WeChatJsSignatureProviderTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private TenantCredentialService credentialService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private WeChatJsSignatureProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        provider = new WeChatJsSignatureProvider(restTemplate, credentialService, new JsTicketCache(redis));
    }

    private void mockCredentials(String tenantId) {
        when(credentialService.get(eq(tenantId), eq("wechat"), eq("app-id")))
                .thenReturn(Optional.of("wx_test_appid"));
        when(credentialService.get(eq(tenantId), eq("wechat"), eq("app-secret")))
                .thenReturn(Optional.of("wx_test_secret"));
    }

    @Test
    void provider_shouldReturnWechat() {
        assertThat(provider.provider()).isEqualTo("wechat");
    }

    @Test
    void sign_throwsWhenCredentialsMissing() {
        when(credentialService.get(any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provider.sign("t1", "https://example.com/page", "default"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app-id 或 app-secret 未配置");
    }

    @Test
    void sign_hitsCache_skipsRestTemplate() {
        mockCredentials("t1");
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("js:ticket:wechat:t1:default")).thenReturn("cached_ticket");

        var sig = provider.sign("t1", "https://example.com/page", "default");

        assertThat(sig.appId()).isEqualTo("wx_test_appid");
        assertThat(sig.url()).isEqualTo("https://example.com/page");
        assertThat(sig.ticketType()).isEqualTo("default");
        assertThat(sig.nonceStr()).hasSize(32);
        assertThat(sig.signature()).hasSize(40);
        // 签名值 = SHA1("jsapi_ticket=cached_ticket&noncestr=N&timestamp=TS&url=U")
        String expected = JsSignatureUtil.sha1Sign("cached_ticket", sig.nonceStr(), sig.timestamp(), sig.url());
        assertThat(sig.signature()).isEqualTo(expected);
        // 缓存命中时不应调用 RestTemplate
        verify(restTemplate, never()).getForObject(anyString(), eq(String.class));
    }

    @Test
    void sign_missesCache_fetchesTokenAndTicket_andCaches() {
        mockCredentials("t1");
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("js:ticket:wechat:t1:default")).thenReturn(null);

        when(restTemplate.getForObject(
                eq("https://api.weixin.qq.com/cgi-bin/token?appid=wx_test_appid&secret=wx_test_secret&grant_type=client_credential"),
                eq(String.class)))
                .thenReturn("{\"access_token\":\"AT\",\"expires_in\":7200}");
        when(restTemplate.getForObject(
                eq("https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=AT&type=jsapi"),
                eq(String.class)))
                .thenReturn("{\"ticket\":\"FRESH_TICKET\",\"errcode\":0,\"errmsg\":\"ok\"}");

        var sig = provider.sign("t1", "https://example.com/page", "default");

        assertThat(sig.signature()).isEqualTo(
                JsSignatureUtil.sha1Sign("FRESH_TICKET", sig.nonceStr(), sig.timestamp(), sig.url()));
        // 验证写入缓存（JsTicketCache.put 内部固定 TTL=7000s）
        verify(valueOps).set("js:ticket:wechat:t1:default", "FRESH_TICKET",
                java.time.Duration.ofSeconds(7000));
    }

    @Test
    void sign_fetchesFailsWhenTicketHasErrcode() {
        mockCredentials("t1");
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("js:ticket:wechat:t1:default")).thenReturn(null);

        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"access_token\":\"AT\"}")
                .thenReturn("{\"ticket\":\"\",\"errcode\":40001,\"errmsg\":\"invalid credential\"}");

        assertThatThrownBy(() -> provider.sign("t1", "https://example.com/page", "default"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("jsapi_ticket 失败");
    }

    @Test
    void default_signDelegatesToDefaultType() {
        mockCredentials("t1");
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn("{\"access_token\":\"AT\"}")
                .thenReturn("{\"ticket\":\"T2\",\"errcode\":0}");

        var sig = provider.sign("t1", "https://example.com/page");

        assertThat(sig.ticketType()).isEqualTo("default");
    }
}
