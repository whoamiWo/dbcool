package com.nocobase.integration.wecom;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.auth.UserEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 企业微信嵌入 REST API。
 *
 * <p>提供：
 * <ul>
 *   <li>SSO 授权地址生成与 code 回调（签发 JWT，对称钉钉 DingTalkController）</li>
 *   <li>通讯录同步（部门/成员）</li>
 *   <li>应用消息发送（真实调用 /cgi-bin/message/send）</li>
 * </ul>
 */
@RestController
@Tag(name = "WeCom Integration", description = "企业微信嵌入")
@RequestMapping("/api/wecom")
public class WeComController {

    private final WeComAppService weComAppService;
    private final RestTemplate restTemplate;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringRedisTemplate redis;

    public WeComController(WeComAppService weComAppService, RestTemplate restTemplate,
                           JwtService jwtService, RefreshTokenService refreshTokenService,
                           StringRedisTemplate redis) {
        this.weComAppService = weComAppService;
        this.restTemplate = restTemplate;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.redis = redis;
    }

    /** 获取企业微信授权地址（前端跳转） */
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl(
            @RequestParam String redirectUri,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        // 企业微信 OAuth2 授权地址
        // https://open.work.weixin.qq.com/wwopen/sso/qrcode?appid=XXX&redirect_uri=XXX&state=YYY
        String state = UUID.randomUUID().toString();
        // state 绑定用户会话，防止 CSRF
        redis.opsForValue().set("wecom:state:" + state,
                user != null ? user.username() : "anonymous", 10, TimeUnit.MINUTES);
        String authUrl = String.format(
                "https://open.work.weixin.qq.com/wwopen/sso/qrcode?appid=%s&redirect_uri=%s&state=%s&agentid=%s",
                weComAppService.getCorpId(),
                redirectUri,
                state,
                weComAppService.getAgentId()
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
        String boundUser = redis.opsForValue().get("wecom:state:" + state);
        if (boundUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 1, "message", "state 无效或已过期"));
        }
        // 一次性使用：校验通过后删除，防止重放
        redis.delete("wecom:state:" + state);
        try {
            UserEntity userEntity = weComAppService.loginFromWeCom(code);
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
                    "message", "企业微信登录失败: " + e.getMessage()
            ));
        }
    }

    /** 触发通讯录同步（按配置的 syncInterval 定时执行，也可手动触发） */
    @PostMapping("/sync-contacts")
    public ResponseEntity<Map<String, Object>> syncContacts(
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (!weComAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "企业微信应用未配置"));
        }
        try {
            Map<String, Object> result = weComAppService.syncContacts();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1, "message", "通讯录同步失败: " + e.getMessage()));
        }
    }

    /** 发送应用消息（真实调用企业微信 /cgi-bin/message/send） */
    @PostMapping("/message/send")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String toUser = (String) body.get("toUser");
        String content = (String) body.get("content");
        if (toUser == null || content == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "toUser 和 content 必填"));
        }
        if (!weComAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "企业微信应用未配置"));
        }
        try {
            String accessToken = weComAppService.getAccessToken();
            String msgUrl = "https://qyapi.weixin.qq.com/cgi-bin/message/send";
            Map<String, Object> payload = Map.of(
                    "touser", toUser,
                    "msgtype", "text",
                    "text", Map.of("content", content),
                    "agentid", weComAppService.getAgentId()
            );
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(accessToken);
            org.springframework.http.HttpEntity<Map<String, Object>> entity =
                    new org.springframework.http.HttpEntity<>(payload, headers);
            String resp = restTemplate.postForObject(msgUrl, entity, String.class);
            JsonNode json = objectMapper.readTree(resp == null ? "{}" : resp);
            int errcode = json.path("errcode").asInt(-1);
            String errmsg = json.path("errmsg").asText("");
            if (errcode != 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "code", 1,
                        "message", "企业微信发送失败: " + errmsg,
                        "errcode", errcode));
            }
            return ResponseEntity.ok(Map.of("code", 0, "message", "sent", "errcode", 0));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "企业微信发送异常: " + e.getMessage()));
        }
    }
}