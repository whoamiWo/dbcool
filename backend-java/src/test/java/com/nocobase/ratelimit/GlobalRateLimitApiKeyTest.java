package com.nocobase.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nocobase.quota.TenantQuotaService;
import com.nocobase.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Wave4 待办池 #8：{@link GlobalRateLimitFilter} 的 API Key 维度限流。
 *
 * <p>验证：带 X-Api-Key 的机器调用按 key 隔离限流；无 key 的请求不受影响；
 * 限流 key 用 SHA-256 前缀，明文密钥不进限流 key。
 */
class GlobalRateLimitApiKeyTest {

    private GlobalRateLimitProperties properties;
    private GlobalRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new GlobalRateLimitProperties();
        // 便于测试：限值调小
        properties.getApiKey().setLimit(3);
        properties.getApiKey().setWindowSeconds(60);
        filter = new GlobalRateLimitFilter(new GlobalRateLimiter(null), properties,
                mock(TenantQuotaService.class), mock(TenantRepository.class));
    }

    @Test
    @DisplayName("同一 API Key 连续调用超过阈值 → 429")
    void sameApiKey_exceedsLimit_returns429() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/collections");
        request.addHeader("X-Api-Key", "key-A-1234567890");
        request.setRemoteAddr("10.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        int lastStatus = 200;
        for (int i = 0; i < 5; i++) {
            response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, resp) -> {});
            lastStatus = response.getStatus();
        }
        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    @DisplayName("不同 API Key 互不影响（各自独立配额）")
    void differentApiKeys_haveIndependentQuota() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest a = new MockHttpServletRequest("GET", "/api/collections");
            a.addHeader("X-Api-Key", "key-A-1234567890");
            filter.doFilter(a, response, (req, resp) -> {});
        }
        // A 已达阈值；换 key B 应放行
        MockHttpServletRequest b = new MockHttpServletRequest("GET", "/api/collections");
        b.addHeader("X-Api-Key", "key-B-abcdef");
        MockHttpServletResponse respB = new MockHttpServletResponse();
        filter.doFilter(b, respB, (req, resp) -> {});
        assertThat(respB.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("无 X-Api-Key 的请求不受 API Key 维度影响")
    void noApiKeyRequest_notLimitedByApiKeyDimension() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/collections");
        MockHttpServletResponse response = new MockHttpServletResponse();
        int lastStatus = 200;
        for (int i = 0; i < 5; i++) {
            response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, resp) -> {});
            lastStatus = response.getStatus();
        }
        assertThat(lastStatus).isNotEqualTo(429);
    }

    @Test
    @DisplayName("限流 key 为哈希前缀，明文密钥不进入 key")
    void shortHash_neverContainsPlaintextKey() {
        String plain = "sk-live-super-secret-1234567890";
        String hash = GlobalRateLimitFilter.shortHash(plain);
        assertThat(hash).hasSize(12);
        assertThat(hash).doesNotContain(plain);
        assertThat(hash).doesNotContain("super-secret");
        // 稳定性：同 key 同哈希
        assertThat(hash).isEqualTo(GlobalRateLimitFilter.shortHash(plain));
        // 区分度：不同 key 不同哈希
        assertThat(hash).isNotEqualTo(GlobalRateLimitFilter.shortHash("other-key"));
    }
}
