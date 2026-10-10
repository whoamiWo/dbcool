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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
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
                    var authorities = new java.util.ArrayList<org.springframework.security.core.authority.SimpleGrantedAuthority>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    // 从 JWT roles claim 添加角色 authorities
                    Object rolesObj = claims.get("roles");
                    if (rolesObj instanceof List<?>) {
                        @SuppressWarnings("unchecked")
                        List<String> roles = (List<String>) rolesObj;
                        for (String roleName : roles) {
                            String normalized = roleName.trim().toUpperCase();
                            if (!normalized.isBlank()) {
                                authorities.add(new SimpleGrantedAuthority("ROLE_" + normalized));
                            }
                        }
                    }
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            authorities
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
    public record AuthenticatedUser(UUID userId, String username, String tenantId)
            implements java.security.Principal {
        @Override
        public String getName() {
            return username;
        }
    }
}
