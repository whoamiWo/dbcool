package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** MDC 过滤器：traceId 透传/生成、租户与用户写入 MDC、结束后必须清理。 */
class MdcFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    /** 在链内捕获 MDC 快照的 FilterChain。 */
    private static final class CapturingChain implements FilterChain {
        String traceId;
        String tenantId;
        String userId;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            this.traceId = MDC.get("traceId");
            this.tenantId = MDC.get("tenantId");
            this.userId = MDC.get("userId");
        }
    }

    private CapturingChain run(MockHttpServletRequest request, CapturingChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MdcFilter().doFilter(request, response, chain);
        return chain;
    }

    @Test
    @DisplayName("上游带 X-Request-ID → 沿用并在响应回写")
    void reusesUpstreamTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "trace-abc");
        CapturingChain chain = new CapturingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MdcFilter().doFilter(request, response, chain);

        assertThat(chain.traceId).isEqualTo("trace-abc");
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("trace-abc");
        // 过滤结束后 MDC 必须清空，避免线程复用串租户
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("上游未带/空 X-Request-ID → 生成 16 位 traceId")
    void generatesTraceIdWhenMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-ID", "   ");
        CapturingChain chain = run(request, new CapturingChain());

        assertThat(chain.traceId).hasSize(16);
    }

    @Test
    @DisplayName("已认证用户 → tenantId/userId 写入 MDC")
    void writesTenantAndUser() throws Exception {
        JwtAuthFilter.AuthenticatedUser user =
                new JwtAuthFilter.AuthenticatedUser(UUID.randomUUID(), "u", "t1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));

        CapturingChain chain = run(new MockHttpServletRequest(), new CapturingChain());

        assertThat(chain.tenantId).isEqualTo("t1");
        assertThat(chain.userId).isNotNull();
    }

    @Test
    @DisplayName("匿名请求 → 只写 traceId，不写租户/用户")
    void anonymousRequest() throws Exception {
        CapturingChain chain = run(new MockHttpServletRequest(), new CapturingChain());

        assertThat(chain.traceId).isNotNull();
        assertThat(chain.tenantId).isNull();
        assertThat(chain.userId).isNull();
    }
}
