package com.nocobase.auth.oidc;

import com.nocobase.auth.JwtService;
import com.nocobase.auth.UserEntity;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * PHASE10: OIDC 认证成功处理器 —— 签发**本地 JWT** 并重定向前端。
 *
 * <p><b>为什么签本地 JWT 而非透传 IdP token</b>：
 * 现有前端各处依赖 {@code /api/auth/login} 返回的本地 JWT（HS256，含 tid/roles）。
 * 若把 IdP 签发的 token 直接给前端，需改造所有请求的 token 来源与解析方式。
 * 这里沿用钉钉/飞书 OAuth 的既有处理模式：外部认证 → 换发本地 JWT → 前端零改造。
 *
 * <p>成功后重定向到 {@code app.oidc.success-redirect}，拼接 {@code ?token=<jwt>}；
 * 前端在该路由读取 token 写入本地存储，随后跳转业务页。
 */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginSuccessHandler.class);

    private final OidcUserService oidcUserService;
    private final JwtService jwtService;
    private final OidcProperties properties;

    public OidcLoginSuccessHandler(OidcUserService oidcUserService,
                                   JwtService jwtService,
                                   OidcProperties properties) {
        this.oidcUserService = oidcUserService;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof OidcUser oidcUser)) {
            log.error("[OIDC] 认证主体不是 OidcUser，无法签发本地 JWT：{}",
                    principal == null ? "null" : principal.getClass().getName());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "OIDC 认证信息无效");
            return;
        }

        try {
            // 1. 映射 / JIT 建号
            UserEntity user = oidcUserService.mapOrCreate(oidcUser);
            // 2. 取角色并签发本地 JWT
            List<String> roles = oidcUserService.rolesOf(user.getId(), user.getTenantId());
            String token = jwtService.issueAccessToken(
                    user.getId(), user.getUsername(), user.getTenantId(), roles);
            // 3. 重定向前端并携带 token
            String redirect = properties.getSuccessRedirect();
            String separator = redirect.contains("?") ? "&" : "?";
            String target = redirect + separator + "token="
                    + URLEncoder.encode(token, StandardCharsets.UTF_8);
            log.info("[OIDC] 登录成功 userId={} username={} → 重定向前端",
                    user.getId(), user.getUsername());
            response.sendRedirect(target);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            log.warn("[OIDC] 登录失败（业务拒绝）status={} reason={}",
                    e.getStatusCode(), e.getReason());
            response.sendError(e.getStatusCode().value(), e.getReason());
        } catch (Exception e) {
            log.error("[OIDC] 登录处理异常：{}", e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "OIDC 登录处理失败");
        }
    }
}
