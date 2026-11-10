package com.nocobase.auth.oidc;

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

/** Maps the OIDC user, then redirects with a short-lived one-time code instead of a JWT. */
@Component
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLoginSuccessHandler.class);

    private final OidcUserService oidcUserService;
    private final OidcLoginCodeService loginCodeService;
    private final OidcProperties properties;

    public OidcLoginSuccessHandler(OidcUserService oidcUserService,
                                   OidcLoginCodeService loginCodeService,
                                   OidcProperties properties) {
        this.oidcUserService = oidcUserService;
        this.loginCodeService = loginCodeService;
        this.properties = properties;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof OidcUser oidcUser)) {
            log.error("[OIDC] 认证主体不是 OidcUser，无法创建一次性登录 code：{}",
                    principal == null ? "null" : principal.getClass().getName());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "OIDC 认证信息无效");
            return;
        }

        try {
            UserEntity user = oidcUserService.mapOrCreate(oidcUser);
            List<String> roles = oidcUserService.rolesOf(user.getId());
            String code = loginCodeService.issue(user.getId(), user.getTenantId(), roles);
            String redirect = properties.getSuccessRedirect();
            String separator = redirect.contains("?") ? "&" : "?";
            String target = redirect + separator + "code="
                    + URLEncoder.encode(code, StandardCharsets.UTF_8);
            // Never log the raw code, an IdP token, or a local JWT.
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
