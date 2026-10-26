package com.nocobase.integration.wechat;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.auth.UserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 微信公众号 / 小程序 REST API。
 *
 * <p>提供：
 * <ul>
 *   <li>SSO 授权地址生成与 code 回调（签发 JWT，对称 WeComController）</li>
 *   <li>小程序 code2Session 登录</li>
 * </ul>
 */
@RestController
@Tag(name = "WeChat Integration", description = "微信公众号/小程序嵌入")
@RequestMapping("/api/wechat")
public class WeChatController {

    private final WeChatAppService weChatAppService;
    private final RestTemplate restTemplate;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public WeChatController(WeChatAppService weChatAppService, RestTemplate restTemplate,
                            JwtService jwtService, RefreshTokenService refreshTokenService,
                            org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.weChatAppService = weChatAppService;
        this.restTemplate = restTemplate;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.redis = redis;
    }

    /** 获取微信公众号授权地址（前端跳转） */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl(
            @RequestParam String redirectUri,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        // 微信公众号 OAuth2 授权地址
        // https://open.weixin.qq.com/connect/oauth2/authorize?appid=APPID&redirect_uri=REDIRECT&response_type=code&scope=snsapi_userinfo&state=STATE#wechat_redirect
        String state = UUID.randomUUID().toString();
        // state 绑定用户会话，防止 CSRF
        redis.opsForValue().set("wechat:state:" + state,
                user != null ? user.username() : "anonymous", 10, TimeUnit.MINUTES);
        String appId = weChatAppService.getAppId();
        String authUrl = String.format(
                "https://open.weixin.qq.com/connect/oauth2/authorize?appid=%s&redirect_uri=%s&response_type=code&scope=snsapi_userinfo&state=%s#wechat_redirect",
                appId,
                redirectUri,
                state
        );
        return ResponseEntity.ok(Map.of("authUrl", authUrl, "state", state));
    }

    /** OAuth2 回调：code 换取用户信息并登录（签发 JWT + refresh token） */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "code 必填"));
        }
        // 校验 state：必须与 auth-url 阶段下发的 state 匹配且在有效期内，防 CSRF
        String state = body.get("state");
        if (state == null || state.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "state 必填"));
        }
        String boundUser = redis.opsForValue().get("wechat:state:" + state);
        if (boundUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 1, "message", "state 无效或已过期"));
        }
        // 一次性使用：校验通过后删除，防止重放
        redis.delete("wechat:state:" + state);
        try {
            UserEntity userEntity = weChatAppService.loginFromWeChat(code);
            String accessToken = jwtService.issueAccessToken(
                    userEntity.getId(), userEntity.getUsername(), userEntity.getTenantId());
            String refreshToken = refreshTokenService.issue(userEntity.getId());
            return ResponseEntity.ok(Map.of(
                    "code", 0,
                    "message", "success",
                    "data", Map.of(
                            "userId", userEntity.getId().toString(),
                            "username", userEntity.getUsername(),
                            "displayName", userEntity.getDisplayName() != null
                                    ? userEntity.getDisplayName() : userEntity.getUsername(),
                            "access_token", accessToken,
                            "refresh_token", refreshToken,
                            "token_type", "Bearer",
                            "expires_in", jwtService.getAccessTtl().toSeconds()
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "微信公众号登录失败：" + e.getMessage()
            ));
        }
    }

    /** 小程序 code2Session 登录 */
    @PostMapping("/mini-program/login")
    public ResponseEntity<Map<String, Object>> miniProgramLogin(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String jsCode = body.get("jsCode");
        if (jsCode == null || jsCode.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "jsCode 必填"));
        }
        try {
            UserEntity userEntity = weChatAppService.loginFromMiniProgram(jsCode);
            String accessToken = jwtService.issueAccessToken(
                    userEntity.getId(), userEntity.getUsername(), userEntity.getTenantId());
            String refreshToken = refreshTokenService.issue(userEntity.getId());
            return ResponseEntity.ok(Map.of(
                    "code", 0,
                    "message", "success",
                    "data", Map.of(
                            "userId", userEntity.getId().toString(),
                            "username", userEntity.getUsername(),
                            "displayName", userEntity.getDisplayName() != null
                                    ? userEntity.getDisplayName() : userEntity.getUsername(),
                            "access_token", accessToken,
                            "refresh_token", refreshToken,
                            "token_type", "Bearer",
                            "expires_in", jwtService.getAccessTtl().toSeconds()
                    )
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "微信小程序登录失败：" + e.getMessage()
            ));
        }
    }
}
