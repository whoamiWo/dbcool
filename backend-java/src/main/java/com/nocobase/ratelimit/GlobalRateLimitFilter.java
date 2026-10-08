package com.nocobase.ratelimit;

import com.nocobase.quota.TenantQuotaService;
import com.nocobase.tenant.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
@ConditionalOnBean(GlobalRateLimiter.class)
public class GlobalRateLimitFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(GlobalRateLimitFilter.class);

    private final GlobalRateLimiter rateLimiter;
    private final GlobalRateLimitProperties properties;
    private final TenantQuotaService tenantQuotaService;
    private final TenantRepository tenantRepository;

    private static final Pattern LOGIN_PATTERN = Pattern.compile("^/api/auth/login$");
    private static final Pattern IM_SEND_PATTERN = Pattern.compile("^/api/im/messages$");
    private static final Pattern UPLOAD_PATTERN = Pattern.compile("^/api/upload$");

    public GlobalRateLimitFilter(GlobalRateLimiter rateLimiter, GlobalRateLimitProperties properties,
                                  TenantQuotaService tenantQuotaService, TenantRepository tenantRepository) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.tenantQuotaService = tenantQuotaService;
        this.tenantRepository = tenantRepository;
    }

    private String extractTenantId(HttpServletRequest request) {
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() != null
                ? org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication().getPrincipal()
                : null;
        if (principal instanceof com.nocobase.auth.JwtAuthFilter.AuthenticatedUser user) {
            return user.tenantId();
        }
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (path == null) {
            path = request.getRequestURI();
        }

        // 注：租户 API 配额**不在这里检查**。
        // 本过滤器注册在 JwtAuthFilter 之前，此刻 SecurityContext 为空，
        // extractTenantId() 恒返回 null —— 放在这里等于永不执行（PHASE92 实测：
        // 压测 109301 个请求，配额拦截数为 0）。
        // 已移到 TenantQuotaFilter（注册在 JwtAuthFilter 之后）执行。

        String key = buildKey(request, path);
        int limit;
        int windowSeconds;

        if (LOGIN_PATTERN.matcher(path).matches()) {
            limit = properties.getLogin().getLimit();
            windowSeconds = properties.getLogin().getWindowSeconds();
        } else if (IM_SEND_PATTERN.matcher(path).matches() && "POST".equals(request.getMethod())) {
            limit = properties.getIm().getLimit();
            windowSeconds = properties.getIm().getWindowSeconds();
        } else if (UPLOAD_PATTERN.matcher(path).matches() && "POST".equals(request.getMethod())) {
            // 存储配额同样不在这里检查（原因同 API 配额：此刻拿不到 tenantId），
            // 已移到 TenantQuotaFilter。
            limit = properties.getFileUpload().getLimit();
            windowSeconds = properties.getFileUpload().getWindowSeconds();
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        if (!rateLimiter.allowRequest(key, limit, windowSeconds)) {
            log.warn("[ratelimit] 触发限流 key={} path={} limit={}/{}", key, path, limit, windowSeconds);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
            return;
        }

        log.debug("[ratelimit] 限流检查通过 key={} path={} remaining={}", key, path, getRemaining(key, limit, windowSeconds));
        filterChain.doFilter(request, response);
    }

    private String buildKey(HttpServletRequest request, String path) {
        String ip = request.getRemoteAddr();
        String userId = extractUserId(request);
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId + ":" + path;
        }
        return "ip:" + ip + ":" + path;
    }

    private String extractUserId(HttpServletRequest request) {
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication() != null
                ? org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication().getPrincipal()
                : null;
        if (principal instanceof com.nocobase.auth.JwtAuthFilter.AuthenticatedUser user) {
            return user.userId().toString();
        }
        return null;
    }

    private int getRemaining(String key, int limit, int windowSeconds) {
        // Simplified: just return 0 for now
        return 0;
    }
}