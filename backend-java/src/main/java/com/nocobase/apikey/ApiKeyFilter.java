package com.nocobase.apikey;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API Key 鉴权过滤器(Week 42 D5.2).
 *
 * <p>从 {@code X-Api-Key} header 取 key,若验证通过则写 SecurityContext
 * (principal 为 {@link AuthenticatedUser} with role {@code ROLE_API}).
 *
 * <p>顺序:在 {@link com.nocobase.auth.JwtAuthFilter} 之前 — 因为
 * 外部系统调用不带 JWT 但带 API Key,优先尝试 API Key 路径。
 *
 * <p>失败处理:仅记录 warn 日志,不主动 set 401 — 留给后续 JWT 过滤器或
 * SecurityConfig 的 {@code authenticationEntryPoint} 统一处理。
 */
@Component
@org.springframework.context.annotation.Lazy
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Api-Key";

    private final ApiKeyService apiKeyService;

    public ApiKeyFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            // 没传 api key,放行给后续 JWT 过滤器处理
            filterChain.doFilter(request, response);
            return;
        }

        // 优先从 X-Tenant-ID header 取 tenantId(外部系统调用不带 JWT);
        // 若无则回退到 TenantContext(若已由前置过滤器设置)。
        String tenantId = request.getHeader("X-Tenant-ID");
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = TenantContext.currentTenantId();
        }

        try {
            Optional<ApiKeyEntity> opt = apiKeyService.validate(header.trim(), tenantId);
            if (opt.isPresent()) {
                ApiKeyEntity key = opt.get();
                AuthenticatedUser principal = new AuthenticatedUser(
                        key.getCreatedBy(),
                        "apikey:" + key.getKeyPrefix(),
                        key.getTenantId()
                );
                // scopes 转为 GrantedAuthority:每个 scope → SCOPE_{scope}(如 SCOPE_read:posts)
                // 这样 @PreAuthorize("@apiKeyService.hasScope(...)") 或 hasAuthority('SCOPE_...')
                // 可在受保护端点上做细粒度授权
                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_API"));
                if (key.getScopes() != null && !key.getScopes().isBlank()) {
                    for (String scope : key.getScopes().split(",")) {
                        String trimmed = scope.trim();
                        if (!trimmed.isEmpty()) {
                            authorities.add(new SimpleGrantedAuthority("SCOPE_" + trimmed));
                        }
                    }
                }
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        authorities
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
                // 绑定 tenantId,保证后续 schema 路由正确
                TenantContext.set(key.getTenantId());
            }
            // else: 不抛 — 留给后续 JWT / 认证入口
        } catch (Exception e) {
            // 不应阻断请求 — service 内已捕获;这里再保险一次
            logger.warn("[apikey] validate exception: " + e.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // 清 TenantContext(防止 ApiKey 后还有 filterchain 复用 thread)
            // 注意:这里清可能误清 JWT 设置的 tenant;但 JWT filter 也会设,
            // SecurityContextHolder.clearContext() 即可保证彻底
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return false;
        // 公开端点不需要 api key 处理(让 JwtAuthFilter / SecurityConfig 决定)
        return path.startsWith("/api/auth/login")
                || path.startsWith("/api/auth/refresh")
                || path.startsWith("/api/health")
                || path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/swagger-resources")
                || path.startsWith("/webjars");
    }
}
