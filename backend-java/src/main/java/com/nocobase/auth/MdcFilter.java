package com.nocobase.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MDC Filter: 注入 tenantId/userId 到 SLF4J MDC，供 JSON 日志输出。
 * Phase 56 P1-1
 */
@Component
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof JwtAuthFilter.AuthenticatedUser user) {
                if (user.tenantId() != null && !user.tenantId().isBlank()) {
                    MDC.put("tenantId", user.tenantId());
                }
                if (user.userId() != null) {
                    MDC.put("userId", user.userId().toString());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
