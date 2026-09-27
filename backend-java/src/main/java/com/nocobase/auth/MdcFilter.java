package com.nocobase.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MDC Filter: 注入 tenantId/userId 到 SLF4J MDC，供 JSON 日志输出。
 *
 * <p>Phase 56 P1-1。
 *
 * <p><b>注册方式（重要）</b>：本类<b>不加</b> {@code @Component}。
 * 若加 {@code @Component}，Servlet 容器会把它注册到 Security 过滤器链<b>之外</b>，
 * 导致它在 JwtAuthFilter 之前执行（此时 SecurityContext 尚未填充，拿不到 principal），
 * 且会与安全链内实例重复执行两次。
 * 正确做法是仅在 {@code SecurityConfig} 中
 * {@code addFilterAfter(new MdcFilter(), JwtAuthFilter.class)} 显式注册，
 * 保证在鉴权之后执行、能读到 AuthenticatedUser。
 */
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
