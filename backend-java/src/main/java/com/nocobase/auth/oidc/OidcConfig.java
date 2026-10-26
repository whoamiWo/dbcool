package com.nocobase.auth.oidc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * PHASE10: OIDC 客户端注册配置。
 *
 * <p><b>关键约束</b>：只有在 {@code app.oidc.enabled=true} 且 issuer-uri / client-id
 * 齐备时才注册 ClientRegistration。未配置时：
 * <ul>
 *   <li>不注册任何 ClientRegistration → {@code /oauth2/authorization/*} 不可用；</li>
 *   <li>但也不会因为缺配置导致应用启动失败（fail-fast 会阻断既有部署）。</li>
 * </ul>
 *
 * <p>registrationId 固定为 {@code nocobase-oidc}，与
 * {@code OidcProperties#loginPath} 及 SecurityConfig 的 loginPage 保持一致。
 */
@Configuration
@EnableConfigurationProperties(OidcProperties.class)
public class OidcConfig {

    private static final Logger log = LoggerFactory.getLogger(OidcConfig.class);

    /** 固定注册 ID —— 决定登录入口路径 /oauth2/authorization/nocobase-oidc。 */
    public static final String REGISTRATION_ID = "nocobase-oidc";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OidcProperties props) {
        if (props.isConfigured()) {
            try {
                // issuer-uri 自动发现：授权端点 / 令牌端点 / JWKS 均从
                // {issuer}/.well-known/openid-configuration 推导
                ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                        .clientId(props.getClientId())
                        .clientSecret(props.getClientSecret())
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .scope(props.scopeList())
                        .issuerUri(props.getIssuerUri())
                        .clientName("企业身份源 (OIDC)")
                        .build();
                log.info("[OIDC] 已注册客户端 registrationId={} issuer={}",
                        REGISTRATION_ID, props.getIssuerUri());
                return new InMemoryClientRegistrationRepository(registration);
            } catch (Exception e) {
                // 配置有误（如 issuer 不可达）时记录错误但不阻断启动
                log.error("[OIDC] 客户端注册失败，OIDC 登录将不可用：{}", e.getMessage());
            }
        } else {
            log.info("[OIDC] 未启用（app.oidc.enabled=false 或缺少 issuer-uri/client-id），"
                    + "不影响既有账密/钉钉/飞书登录");
        }

        // 未配置时返回空仓库 —— Spring Security 的 InMemoryClientRegistrationRepository
        // 构造函数校验 registrations 非空，故不能直接传空列表（会破坏 Spring 上下文）。
        return new EmptyClientRegistrationRepository();
    }

    /** 空仓库实现，避免 InMemoryClientRegistrationRepository 的非空校验。 */
    private static final class EmptyClientRegistrationRepository implements ClientRegistrationRepository {
        @Override
        public ClientRegistration findByRegistrationId(String registrationId) {
            return null;
        }
    }
}
