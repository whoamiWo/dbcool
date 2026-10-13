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
 * PHASE99 T2 专项测试：{@link GlobalRateLimitFilter} 对第三方回调入站端点的限流。
 *
 * <p>验证验收标准：
 * <ul>
 *   <li>短时间内对回调端点打 N 次（超过阈值）出现 429；</li>
 *   <li>关掉限流后不出现 429（对照验证，防恒真）。</li>
 * </ul>
 */
class GlobalRateLimitInboundTest {

    private GlobalRateLimiter rateLimiter;
    private GlobalRateLimitProperties properties;
    private GlobalRateLimitFilter filter;
    private TenantQuotaService quotaService;
    private TenantRepository tenantRepository;

    @BeforeEach
    void setUp() {
        properties = new GlobalRateLimitProperties();
        // 默认 inbound: 60 次/60 秒
        rateLimiter = new GlobalRateLimiter(null);
        TenantQuotaService quotaService = mock(TenantQuotaService.class);
        TenantRepository tenantRepository = mock(TenantRepository.class);
        filter = new GlobalRateLimitFilter(rateLimiter, properties, quotaService, tenantRepository);
    }

    @Test
    @DisplayName("Inbound 端点超过阈值返回 429")
    void inboundEndpoint_exceedsLimit_returns429() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/slack/events");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 打 65 次（阈值 60）
        int lastStatus = 200;
        for (int i = 0; i < 65; i++) {
            filter.doFilter(request, response, (req, resp) -> {});
            lastStatus = response.getStatus();
        }

        assertThat(lastStatus).isEqualTo(429);
    }

    @Test
    @DisplayName("关掉限流后不返回 429（对照验证，防恒真）")
    void inboundEndpoint_limitDisabled_neverReturns429() throws Exception {
        // 把 limit 调到极大（等价于关闭限流）
        properties.getInbound().setLimit(Integer.MAX_VALUE);
        properties.getInbound().setWindowSeconds(60);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/slack/events");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 100; i++) {
            filter.doFilter(request, response, (req, resp) -> {});
        }

        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("Inbound 端点未超阈值正常放行")
    void inboundEndpoint_withinLimit_passes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/slack/events");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, resp) -> {});

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("不同 provider 独立计数（不互相挤兑）")
    void inboundEndpoint_differentProviders_independentCounters() throws Exception {
        // 打满 slack 的限额
        MockHttpServletRequest slackReq = new MockHttpServletRequest("POST", "/api/slack/events");
        slackReq.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse slackResp = new MockHttpServletResponse();
        for (int i = 0; i < 65; i++) {
            filter.doFilter(slackReq, slackResp, (req, resp) -> {});
        }
        assertThat(slackResp.getStatus()).isEqualTo(429);

        // feishu 应该还能用（独立计数器）
        MockHttpServletRequest feishuReq = new MockHttpServletRequest("POST", "/api/feishu/events");
        feishuReq.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse feishuResp = new MockHttpServletResponse();
        filter.doFilter(feishuReq, feishuResp, (req, resp) -> {});
        assertThat(feishuResp.getStatus()).isEqualTo(200);
    }
}
