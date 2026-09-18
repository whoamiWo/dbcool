package com.nocobase.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiKeyFilterTest {

    private ApiKeyService service;
    private ApiKeyFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        service = mock(ApiKeyService.class);
        filter = new ApiKeyFilter(service);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void noHeader_passesThrough() throws Exception {
        when(request.getHeader(ApiKeyFilter.HEADER_NAME)).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(service, never()).validate(anyString(), anyString());
        verify(chain, times(1)).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void blankHeader_passesThrough() throws Exception {
        when(request.getHeader(ApiKeyFilter.HEADER_NAME)).thenReturn("  ");

        filter.doFilter(request, response, chain);

        verify(service, never()).validate(anyString(), anyString());
        verify(chain).doFilter(request, response);
    }

    @Test
    void invalidKey_passesThroughNoAuth() throws Exception {
        when(request.getHeader(ApiKeyFilter.HEADER_NAME)).thenReturn("ncb_invalid");
        when(service.validate(anyString(), anyString())).thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(service).validate(eq("ncb_invalid"), isNull());
        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validKey_setsAuthenticationAndTenant() throws Exception {
        String rawKey = "ncb_" + "a".repeat(32);
        when(request.getHeader(ApiKeyFilter.HEADER_NAME)).thenReturn(rawKey);
        ApiKeyEntity e = new ApiKeyEntity();
        e.setId(UUID.randomUUID());
        e.setKeyPrefix("ncb_aaa");
        e.setKeyHash("x");
        e.setTenantId("acme");
        e.setCreatedBy(UUID.randomUUID());
        e.setCreatedAt(java.time.Instant.now());
        when(service.validate(eq(rawKey), isNull())).thenReturn(Optional.of(e));
        // 让 TenantContext.currentTenantId() 不抛 — 模拟请求已带 JWT tenant
        TenantContext.set("default-tenant");

        filter.doFilter(request, response, chain);

        // SecurityContextHolder 应有 ROLE_API 身份
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting("authority").contains("ROLE_API");
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldNotFilter_skipsPublicEndpoints() {
        // 公开端点直接跳过
        for (String path : new String[]{
                "/api/auth/login",
                "/api/auth/refresh",
                "/api/health",
                "/actuator/health",
                "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html"
        }) {
            when(request.getRequestURI()).thenReturn(path);
            assertThat(filter.shouldNotFilter(request))
                    .as("path %s should skip filter", path)
                    .isTrue();
        }
    }

    @Test
    void shouldNotFilter_runsForApiEndpoints() {
        when(request.getRequestURI()).thenReturn("/api/collections/posts");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }
}
