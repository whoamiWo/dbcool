package com.nocobase.config;

import com.nocobase.apikey.ApiKeyFilter;
import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.MdcFilter;
import com.nocobase.ratelimit.GlobalRateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security 6 配置.
 *
 * <p>Week 4:启用 JWT 过滤器,CORS 放开,部分端点免认证.
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true, proxyTargetClass = true)
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    /** Week 42 D5.2: API Key 鉴权过滤器，先于 JWT 尝试。 */
    private final ApiKeyFilter apiKeyFilter;
    /** PHASE 60 R1: 全局限流过滤器，早于认证返回 401。 */
    private final GlobalRateLimitFilter globalRateLimitFilter;
    private final com.nocobase.quota.TenantQuotaFilter tenantQuotaFilter;

    /** PHASE114: 统一 CORS/WS 允许来源，读取 app.cors.allowed-origins（默认 dev 本地） */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:3000,http://localhost}")
    private String allowedOriginsRaw;

    /** PHASE10: OIDC 登录成功处理器（未启用 OIDC 时为 null，不参与装配）。 */
    private final com.nocobase.auth.oidc.OidcLoginSuccessHandler oidcSuccessHandler;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyFilter apiKeyFilter,
                          GlobalRateLimitFilter globalRateLimitFilter,
                          com.nocobase.quota.TenantQuotaFilter tenantQuotaFilter,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                                  com.nocobase.auth.oidc.OidcLoginSuccessHandler oidcSuccessHandler) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyFilter = apiKeyFilter;
        this.globalRateLimitFilter = globalRateLimitFilter;
        this.tenantQuotaFilter = tenantQuotaFilter;
        this.oidcSuccessHandler = oidcSuccessHandler;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @org.springframework.context.annotation.Primary
    public org.springframework.security.authentication.AuthenticationManager authenticationManager(
            org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 公开端点
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/api/health/**").permitAll()
                        // 钉钉嵌入:扫码登录与服务端回调无法携带 JWT,必须匿名放行。
                        // Stage 1 安全收口:审批回调的 HMAC-SHA256 签名校验(含 5 分钟时间窗 + 常量时间比较)
                        // 在 DingTalkController.approvalCallback 内完成,签名无效返 401(见 L187-256)。
                        // SecurityConfig 仅负责链路可达,不做裸放行。
                        .requestMatchers(HttpMethod.POST, "/api/dingtalk/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/dingtalk/auth-url").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/dingtalk/auth-url").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/dingtalk/approval-callback").permitAll()
                        // PHASE62: 第三方 webhook 回调端点必须匿名放行 ——
                        // Slack / 飞书 / 钉钉 / 企微 / Mattermost 的回调请求**不可能**携带我方 JWT,
                        // 若不放行则全部 401,入站链路在生产上形同虚设(实测:不带 token → 401)。
                        // 安全防线**不在**这里,而在各 Controller 内的签名校验:
                        //   - Slack    : SlackController:129 verifySignature(HMAC-SHA256 + 5 分钟窗)
                        //   - 钉钉     : DingTalkController events(HMAC-SHA256 + Base64)
                        //   - Mattermost: token 校验,且 require-token 默认 true(fail-close)
                        // 签名无效一律 401,故放行不等于裸奔。
                        .requestMatchers(HttpMethod.POST, "/api/slack/events").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/feishu/events").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/mattermost/webhook/incoming").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/wecom/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/dingtalk/events").permitAll()
                        // Actuator: 生产 profile 下仅 health 匿名，其他需要认证
                        .requestMatchers("/actuator/health").permitAll()
                        // PHASE104 审计修正：Prometheus 的 scrape 是匿名 HTTP GET，无法携带 JWT。
                        // 实测 /actuator/prometheus 返回 401 → Prometheus 抓取目标永远 down，
                        // 5xx / P99 告警规则取不到数据，整个监控链路空转。
                        // 此处**仅**放行 metrics 端点；/actuator/env、/actuator/heapdump 等
                        // 敏感端点仍由下一行 /actuator/** 要求认证。
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        // 统一实时消息总线:WS 握手无法携带 Authorization 头,
                        // 鉴权交由 StompHandshakeInterceptor 在握手阶段完成。
                        // 注意只放行 /ws/im/** —— 不放行 /ws/**,避免既有 /ws/alerts
                        // (原生端点,自身无鉴权)被一并暴露。
                        .requestMatchers("/ws/im/**").permitAll()
                        // Huddle WebRTC 信令:原生 WS,同样无法携带 Authorization 头,
                        // 握手鉴权由 StompHandshakeInterceptor 统一完成。
                        .requestMatchers("/ws/huddle").permitAll()
                        // PHASE10: 公开表单填写（免登录）—— 外部用户凭 share_token 访问。
                        // 安全防线不在链路放行，而在 FormService.getByShareToken 的
                        // token 查表校验：无效/已撤销一律 404（不返回 403，
                        // 避免泄露表单是否存在）。与钉钉/飞书回调同一放行原则。
                        .requestMatchers(HttpMethod.GET, "/api/forms/public/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/forms/public/*/submit").permitAll()
                        // Swagger / OpenAPI — 仅 dev profile 放行，生产默认关闭
                        // PHASE95 R4: /v3/api-docs 在 docker/profile 已关闭
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                        "/swagger-resources/**", "/webjars/**").permitAll()
                        // 其他全部需要认证
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        // 只有 AuthenticationException / AccessDeniedException 才走这里
                        // 业务异常 (ResponseStatusException) 由 Spring MVC 的 @ControllerAdvice 处理
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"code\":1001,\"message\":\"未认证\",\"data\":{}}");
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setContentType("application/json");
                            res.getWriter().write("{\"code\":1002,\"message\":\"无权限\",\"data\":{}}");
                        })
                )
                .addFilterBefore(globalRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                // Week 42 D5.2: API Key filter 先于 JWT — 外部系统用 X-Api-Key
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // PHASE92：租户配额必须在**认证之后**才能拿到 tenantId
                // （放在 JwtAuthFilter 之前会因 SecurityContext 为空而整段跳过，
                //  表现为"配额写了但从不生效"）。
                // 注意：addFilterAfter 必须写在 addFilterBefore(jwtAuthFilter, …)
                // **之后** —— 否则 JwtAuthFilter 尚未注册 order，启动直接失败：
                // "The Filter class …JwtAuthFilter does not have a registered order"。
                .addFilterAfter(tenantQuotaFilter, JwtAuthFilter.class)
                // PHASE 56 P1-1: MDC 必须在 JWT 鉴权之后,才能从 SecurityContext
                // 读到 AuthenticatedUser 并注入 tenantId/userId 到日志 MDC。
                .addFilterAfter(new MdcFilter(), JwtAuthFilter.class);

        // PHASE10: OIDC 通用 SSO —— 仅在已配置且处理器可用时启用。
        // 未配置（app.oidc.enabled=false 或缺少 client-id）时完全不注册，
        // 保证既有账密 / 钉钉 / 飞书登录链路不受影响。
        if (oidcSuccessHandler != null) {
            http.oauth2Login(oauth2 -> oauth2
                    .successHandler(oidcSuccessHandler)
                    // 登录入口：/oauth2/authorization/{registrationId}
                    .loginPage("/oauth2/authorization/nocobase-oidc"));
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class)
                    .info("[SecurityConfig] OIDC 登录已启用（授权码流程 → 签发本地 JWT）");
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins());
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /** PHASE114: 解析逗号分隔的允许来源列表，供 REST CORS 与 WebSocket 端点共用。 */
    public java.util.List<String> allowedOrigins() {
        return java.util.Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
