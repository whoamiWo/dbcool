package com.nocobase.quota;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/**
 * PHASE92：租户 API 配额过滤器 —— **必须注册在 JwtAuthFilter 之后**。
 *
 * <p><b>为什么单独一个过滤器</b>：配额要按租户计数，租户只能从认证信息里取
 * （{@code AuthenticatedUser.tenantId()}）。而 {@code GlobalRateLimitFilter}
 * 注册在 {@code JwtAuthFilter} <b>之前</b>（用于认证前防洪水），
 * 在它里面做配额时 SecurityContext 还是空的，取不到租户 →
 * 配额判断被整段跳过（实测：压测 109301 个请求，429 拦截数为 0）。
 *
 * <p><b>为什么用 ApplicationContext 延迟取 TenantQuotaService，而不是构造注入</b>：
 * SecurityConfig 依赖本过滤器，而所有 {@code @WebMvcTest} 切片测试都会加载
 * SecurityConfig —— 构造注入会让它们因缺少 {@code TenantQuotaService}（@Service）
 * 而全部启动失败（实测 107 个 Errors）。延迟获取 + 取不到就跳过，
 * 切片测试无需任何改动；生产环境该 Bean 一定存在，配额照常生效。
 */
@Component
@Order(0)
public class TenantQuotaFilter extends OncePerRequestFilter implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaFilter.class);

    private ApplicationContext applicationContext;
    private volatile TenantQuotaService quotaService;
    private volatile boolean lookupFailed;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /** 延迟获取配额服务；不存在（如切片测试）时返回 null —— 此时配额不生效但不阻断请求。 */
    private TenantQuotaService quota() {
        if (quotaService == null && !lookupFailed && applicationContext != null) {
            try {
                quotaService = applicationContext.getBean(TenantQuotaService.class);
            } catch (Exception e) {
                lookupFailed = true;
                log.debug("[quota] 未找到 TenantQuotaService，配额不生效：{}", e.toString());
            }
        }
        return quotaService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        TenantQuotaService quota = quota();
        String tenantId = currentTenantId();
        if (quota != null && tenantId != null) {
            try {
                // 超限由 Service 抛 ResponseStatusException（429），此处捕获后写响应
                quota.checkAndConsumeApiQuota(tenantId);

                // 存储配额：上传类请求按 Content-Length 校验累计用量
                String uri = request.getRequestURI();
                if (uri != null && uri.contains("/upload")) {
                    long size = contentLength(request);
                    if (size > 0) {
                        quota.checkStorageQuota(tenantId, size);
                    }
                }
            } catch (ResponseStatusException e) {
                int status = e.getStatusCode().value();
                log.warn("[quota] 配额拦截 tenant={} status={} path={}",
                        tenantId, status, request.getRequestURI());
                response.setStatus(status);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":" + status + ",\"message\":\"" + e.getReason() + "\"}");
                return;
            } catch (Exception e) {
                // 非配额类异常不得阻塞业务（与 AuditService 同样的容错取向）
                log.error("[quota] 配额检查异常，放行 path={} err={}",
                        request.getRequestURI(), e.toString());
            }
        }
        chain.doFilter(request, response);
    }

    private long contentLength(HttpServletRequest request) {
        String v = request.getHeader("Content-Length");
        if (v == null) return 0;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String currentTenantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return principal instanceof AuthenticatedUser user ? user.tenantId() : null;
    }
}
