package com.nocobase.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtService;
import com.nocobase.auth.UserEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

/**
 * D4：OIDC「首次登录」切片测试。
 *
 * <p>OIDC 没有自定义 callback Controller，成功路径就是
 * {@link OidcLoginSuccessHandler#onAuthenticationSuccess}。这里 mock IdP 主体
 * 与 JwtService，验证成功重定向 / 业务拒绝 / 非法主体 / 带 query 的 redirect。
 */
class OidcLoginSuccessHandlerTest {

    private OidcUserService oidcUserService;
    private JwtService jwtService;
    private OidcProperties properties;
    private OidcLoginSuccessHandler handler;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        oidcUserService = mock(OidcUserService.class);
        jwtService = mock(JwtService.class);
        properties = new OidcProperties();
        properties.setSuccessRedirect("http://localhost:5173/oidc/callback");
        handler = new OidcLoginSuccessHandler(oidcUserService, jwtService, properties);
    }

    private Authentication authWith(Object principal) {
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        return auth;
    }

    private UserEntity mappedUser() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername("alice");
        user.setTenantId("tenant_default");
        user.setPasswordSet(false);
        return user;
    }

    @Test
    @DisplayName("成功：签发本地 JWT 并重定向 ?token=")
    void success_redirectsWithToken() throws Exception {
        when(oidcUserService.mapOrCreate(any())).thenReturn(mappedUser());
        when(oidcUserService.rolesOf(userId, "tenant_default")).thenReturn(List.of("USER"));
        when(jwtService.issueAccessToken(userId, "alice", "tenant_default", List.of("USER")))
                .thenReturn("ACCESS.jwt");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(mock(OidcUser.class)));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .startsWith("http://localhost:5173/oidc/callback")
                .contains("token=ACCESS.jwt");
        verify(jwtService).issueAccessToken(userId, "alice", "tenant_default", List.of("USER"));
    }

    @Test
    @DisplayName("业务拒绝：403 且不签发 token")
    void businessRejection_returns403() throws Exception {
        when(oidcUserService.mapOrCreate(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "租户未授权"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(mock(OidcUser.class)));

        assertThat(response.getStatus()).isEqualTo(403);
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("非法主体：401 且不调用 mapOrCreate")
    void nonOidcPrincipal_returns401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(new Object()));

        assertThat(response.getStatus()).isEqualTo(401);
        verify(oidcUserService, never()).mapOrCreate(any());
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), anyList());
    }

    @Test
    @DisplayName("redirect 已带 query → 使用 & 拼接 token")
    void redirectWithExistingQuery_usesAmpersand() throws Exception {
        properties.setSuccessRedirect("http://x/cb?a=1");
        when(oidcUserService.mapOrCreate(any())).thenReturn(mappedUser());
        when(oidcUserService.rolesOf(userId, "tenant_default")).thenReturn(List.of("USER"));
        when(jwtService.issueAccessToken(userId, "alice", "tenant_default", List.of("USER")))
                .thenReturn("ACCESS.jwt");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(mock(OidcUser.class)));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://x/cb?a=1&token=ACCESS.jwt");
    }
}
