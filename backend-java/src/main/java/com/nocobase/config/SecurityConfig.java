package com.nocobase.config;

import com.nocobase.apikey.ApiKeyFilter;
import com.nocobase.auth.JwtAuthFilter;
import com.nocobase.auth.MdcFilter;
import com.nocobase.ratelimit.GlobalRateLimitFilter;
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
import org.springframework.util.StringUtils;
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

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ApiKeyFilter apiKeyFilter,
                          GlobalRateLimitFilter globalRateLimitFilter,
                          com.nocobase.quota.TenantQuotaFilter tenantQuotaFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.apiKeyFilter = apiKeyFilter;
        this.globalRateLimitFilter = globalRateLimitFilter;
        this.tenantQuotaFilter = tenantQuotaFilter;
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
                        .requestMatchers("/actuator/**").authenticated()
                        // 统一实时消息总线:WS 握手无法携带 Authorization 头,
                        // 鉴权交由 StompHandshakeInterceptor 在握手阶段完成。
                        // 注意只放行 /ws/im/** —— 不放行 /ws/**,避免既有 /ws/alerts
                        // (原生端点,自身无鉴权)被一并暴露。
                        .requestMatchers("/ws/im/**").permitAll()
                        // Huddle WebRTC 信令:原生 WS,同样无法携带 Authorization 头,
                        // 握手鉴权由 StompHandshakeInterceptor 统一完成。
                        .requestMatchers("/ws/huddle").permitAll()
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

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(java.util.List.of(
                "http://localhost:5173",
                "http://localhost:3000",
                "http://localhost"
        ));
        config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(java.util.List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
