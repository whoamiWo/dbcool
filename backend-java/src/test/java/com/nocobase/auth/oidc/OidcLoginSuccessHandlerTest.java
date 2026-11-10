package com.nocobase.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.auth.JwtService;
import com.nocobase.auth.RoleRepository;
import com.nocobase.auth.UserEntity;
import com.nocobase.auth.UserProvisioningService;
import com.nocobase.auth.UserRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.server.ResponseStatusException;

class OidcLoginSuccessHandlerTest {

    private OidcUserService oidcUserService;
    private OidcLoginCodeService loginCodeService;
    private JwtService jwtService;
    private OidcProperties properties;
    private OidcLoginSuccessHandler handler;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        oidcUserService = mock(OidcUserService.class);
        loginCodeService = mock(OidcLoginCodeService.class);
        jwtService = mock(JwtService.class);
        properties = new OidcProperties();
        properties.setSuccessRedirect("http://localhost:5173/oidc/callback");
        handler = new OidcLoginSuccessHandler(oidcUserService, loginCodeService, properties);
    }

    @AfterEach
    void clearTenantContext() {
        com.nocobase.tenant.TenantContext.clear();
    }

    private OidcUser oidcUser(String subject) {
        org.springframework.security.oauth2.core.oidc.OidcIdToken idToken =
                new org.springframework.security.oauth2.core.oidc.OidcIdToken(
                        "id-token", null, null, java.util.Map.of(
                                "sub", subject, "groups", List.of("ADMIN")));
        return new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(List.of(), idToken);
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
        user.setEnabled(true);
        user.setStatus("ACTIVE");
        return user;
    }

    @Test
    @DisplayName("成功：只创建一次性 code 并重定向，不创建或暴露 JWT")
    void success_redirectsOnlyWithCode() throws Exception {
        when(oidcUserService.mapOrCreate(any())).thenReturn(mappedUser());
        when(oidcUserService.rolesOf(userId)).thenReturn(List.of("USER"));
        when(loginCodeService.issue(userId, "tenant_default", List.of("USER")))
                .thenReturn("one-time-code+/=");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(mock(OidcUser.class)));

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("http://localhost:5173/oidc/callback?code=one-time-code%2B%2F%3D");
        assertThat(response.getRedirectedUrl()).doesNotContain("token=", "ACCESS", "jwt");
        verify(loginCodeService).issue(userId, "tenant_default", List.of("USER"));
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("本地角色绑定进入一次性 code，IdP groups 不授予 ADMIN")
    void localRoles_areBoundToLoginCode() throws Exception {
        UserRepository users = mock(UserRepository.class);
        UserProvisioningService provisioning = mock(UserProvisioningService.class);
        RoleRepository roles = mock(RoleRepository.class);
        UserEntity mapped = mappedUser();
        com.nocobase.tenant.TenantContext.set("tenant_other");
        when(users.findByTenantIdAndExternalSourceAndExternalId("tenant_default", "OIDC", "sub-1"))
                .thenReturn(java.util.Optional.of(mapped));
        when(roles.findRoleNamesByUserId(userId, "tenant_default")).thenReturn(List.of("editor"));
        OidcUserService realUserService = new OidcUserService(users, provisioning, roles);
        OidcLoginCodeRepository loginCodes = mock(OidcLoginCodeRepository.class);
        OidcLoginCodeService realCodeService = new OidcLoginCodeService(
                loginCodes, users, jwtService, new com.fasterxml.jackson.databind.ObjectMapper());
        OidcLoginSuccessHandler realHandler = new OidcLoginSuccessHandler(
                realUserService, realCodeService, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        realHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(oidcUser("sub-1")));

        String redirect = response.getRedirectedUrl();
        String code = URLDecoder.decode(redirect.replaceFirst("^.*[?&]code=", ""), StandardCharsets.UTF_8);
        assertThat(code).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(redirect).containsOnlyOnce("code=").doesNotContain("token=", "groups");
        org.mockito.ArgumentCaptor<OidcLoginCodeEntity> saved =
                org.mockito.ArgumentCaptor.forClass(OidcLoginCodeEntity.class);
        verify(loginCodes).save(saved.capture());
        assertThat(saved.getValue().getCodeHash()).isEqualTo(
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(code.getBytes(StandardCharsets.UTF_8))));
        assertThat(saved.getValue().getCodeHash()).doesNotContain(code);
        assertThat(saved.getValue().getRolesJson()).isEqualTo("[\"editor\"]");
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getTenantId()).isEqualTo("tenant_default");
        assertThat(java.time.Duration.between(saved.getValue().getCreatedAt(),
                saved.getValue().getExpiresAt())).isEqualTo(java.time.Duration.ofSeconds(60));
        verify(roles).findRoleNamesByUserId(userId, "tenant_default");
        verify(provisioning, never()).createExternal(any(), any(), any(), any(), any(), any());
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("业务拒绝：403 且不创建 code 或 token")
    void businessRejection_returns403() throws Exception {
        when(oidcUserService.mapOrCreate(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "租户未授权"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(mock(OidcUser.class)));

        assertThat(response.getStatus()).isEqualTo(403);
        verify(loginCodeService, never()).issue(any(), any(), any());
        verify(jwtService, never()).issueAccessToken(any(), any(), any(), any());
    }

    @Test
    @DisplayName("非法主体：401 且不调用 mapOrCreate")
    void nonOidcPrincipal_returns401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(new Object()));

        assertThat(response.getStatus()).isEqualTo(401);
        verify(oidcUserService, never()).mapOrCreate(any());
        verify(loginCodeService, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("redirect 已带 query → 使用 & 拼接 code")
    void redirectWithExistingQuery_usesAmpersand() throws Exception {
        properties.setSuccessRedirect("http://x/cb?a=1");
        when(oidcUserService.mapOrCreate(any())).thenReturn(mappedUser());
        when(loginCodeService.issue(any(), any(), any())).thenReturn("one-time-code");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                authWith(mock(OidcUser.class)));

        assertThat(response.getRedirectedUrl()).isEqualTo("http://x/cb?a=1&code=one-time-code");
    }
}
