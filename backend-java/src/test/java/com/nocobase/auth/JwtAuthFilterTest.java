package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.nocobase.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** JWT 鉴权过滤器：解析成功写入上下文与租户，失败不得污染上下文。 */
class JwtAuthFilterTest {

    private JwtService jwtService;
    private JwtAuthFilter filter;
    private boolean chainCalled;
    private String tenantDuringChain;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        filter = new JwtAuthFilter(jwtService);
        chainCalled = false;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void runFilter(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            chainCalled = true;
            tenantDuringChain = TenantContext.currentTenantId();
        });
    }

    private Claims claims(String username, String tenantId, Object roles) {
        Claims c = mock(Claims.class);
        when(c.getSubject()).thenReturn(UUID.randomUUID().toString());
        when(c.get("username")).thenReturn(username);
        when(c.get("tid")).thenReturn(tenantId);
        when(c.get("roles")).thenReturn(roles);
        return c;
    }

    @Test
    @DisplayName("有效 Bearer token → 写入认证、角色与租户上下文")
    void validToken_setsContextAndTenant() throws Exception {
        Claims goodClaims = claims("alice", "t1", List.of("admin", " "));
        when(jwtService.parseAccessToken("good")).thenReturn(goodClaims);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good");
        runFilter(request);

        assertThat(chainCalled).isTrue();
        JwtAuthFilter.AuthenticatedUser user =
                (JwtAuthFilter.AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.tenantId()).isEqualTo("t1");
        List<String> authorities = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(authorities).contains("ROLE_USER", "ROLE_ADMIN");
        // 链内租户已绑定
        assertThat(tenantDuringChain).isEqualTo("t1");
        // 过滤结束后租户上下文必须清理，避免线程池复用把 t1 带给下一个请求
        assertThat(TenantContext.currentTenantId()).isNotEqualTo("t1");
    }

    @Test
    @DisplayName("token 解析失败（claims 为 null）→ 不设置认证，请求继续")
    void invalidToken_continuesWithoutAuth() throws Exception {
        when(jwtService.parseAccessToken("bad")).thenReturn(null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");
        runFilter(request);

        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("无 Authorization 头 → 直接放行")
    void noHeader_passesThrough() throws Exception {
        runFilter(new MockHttpServletRequest());

        assertThat(chainCalled).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("roles 不是 List → 仍完成认证（只带 ROLE_USER）")
    void nonListRoles_stillAuthenticates() throws Exception {
        Claims stringRolesClaims = claims("bob", "t2", "admin");
        when(jwtService.parseAccessToken("t")).thenReturn(stringRolesClaims);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");
        runFilter(request);

        List<String> authorities = SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(authorities).containsExactly("ROLE_USER");
    }
}
