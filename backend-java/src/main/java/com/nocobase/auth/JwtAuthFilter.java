package com.nocobase.auth;

import com.nocobase.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 鉴权过滤器.
 *
 * <p>从 Authorization: Bearer {token} 解析 JWT,写入 SecurityContext.
 * 若 token 缺失或非法,放行(交给 SecurityConfig 决定是否拦截).
 *
 * <p>Week 41 D6 Step G1:解析 JWT 后设置 TenantContext,finally 清零防线程复用泄漏。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                Claims claims = jwtService.parseAccessToken(token);
                if (claims != null) {
                    UUID userId = UUID.fromString(claims.getSubject());
                    String username = (String) claims.get("username");
                    String tenantId = (String) claims.get("tid");

                    AuthenticatedUser principal = new AuthenticatedUser(userId, username, tenantId);
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    // Week 41 D6 Step G1:绑定 tenantId 到 ThreadLocal
                    if (tenantId != null && !tenantId.isBlank()) {
                        TenantContext.set(tenantId);
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // 重要:防止线程复用导致 tenantId 泄漏到下一个请求
            TenantContext.clear();
        }
    }

    /**
     * 已认证用户(写入 SecurityContext 的 principal).
     */
    public record AuthenticatedUser(UUID userId, String username, String tenantId) {}
}
