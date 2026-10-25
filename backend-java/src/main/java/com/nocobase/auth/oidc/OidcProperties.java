package com.nocobase.auth.oidc;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PHASE10: OIDC 通用 SSO 配置（绑定 app.oidc）。
 *
 * <p>对接企业已有身份源：Keycloak / Azure AD / Okta / AD FS 等标准 OIDC 提供方。
 *
 * <p><b>缺省即禁用</b>：{@code enabled=false} 或未配置 client-id 时不注册
 * ClientRegistration，不影响既有账密 / 钉钉 / 飞书登录链路。
 *
 * <p>配置示例（application.yml 的 app 节下）：
 * <pre>
 * app:
 *   oidc:
 *     enabled: true
 *     issuer-uri: http://keycloak:8080/realms/nocobase
 *     client-id: nocobase-web
 *     client-secret: xxx
 *     scope: openid,profile,email
 *     success-redirect: http://localhost:5173/oidc/callback
 * </pre>
 */
@ConfigurationProperties(prefix = "app.oidc")
public class OidcProperties {

    /** 是否启用 OIDC 登录。 */
    private boolean enabled = false;

    /** IdP 发现地址（issuer-uri），用于自动发现授权/令牌/JWKS 端点。 */
    private String issuerUri = "";

    /** 客户端 ID（由 IdP 颁发）。 */
    private String clientId = "";

    /** 客户端密钥（由 IdP 颁发；生产应由 KMS/密钥管理注入）。 */
    private String clientSecret = "";

    /** 申请的 scope，默认 openid,profile,email。 */
    private String scope = "openid,profile,email";

    /**
     * 登录成功后重定向前端的地址（会拼接 ?token=）。
     * 前端需在该路由读取 token 并写入本地存储。
     */
    private String successRedirect = "http://localhost:5173/oidc/callback";

    /** 登录入口路径（前端跳转到此路径触发 IdP 授权）。 */
    private String loginPath = "/oauth2/authorization/nocobase-oidc";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getSuccessRedirect() {
        return successRedirect;
    }

    public void setSuccessRedirect(String successRedirect) {
        this.successRedirect = successRedirect;
    }

    public String getLoginPath() {
        return loginPath;
    }

    public void setLoginPath(String loginPath) {
        this.loginPath = loginPath;
    }

    /** scope 字符串转列表（逗号分隔）。 */
    public List<String> scopeList() {
        if (scope == null || scope.isBlank()) {
            return List.of("openid", "profile", "email");
        }
        return java.util.Arrays.stream(scope.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 是否具备启用条件（开关打开且必填项齐备）。 */
    public boolean isConfigured() {
        return enabled
                && issuerUri != null && !issuerUri.isBlank()
                && clientId != null && !clientId.isBlank();
    }
}
