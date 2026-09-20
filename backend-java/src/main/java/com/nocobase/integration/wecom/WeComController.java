package com.nocobase.integration.wecom;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.auth.UserEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

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

    public WeComController(WeComAppService weComAppService, RestTemplate restTemplate,
                           JwtService jwtService, RefreshTokenService refreshTokenService) {
        this.weComAppService = weComAppService;
        this.restTemplate = restTemplate;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
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
        // TODO: 调用企业微信通讯录 API 同步部门/成员
        // 1. 获取 access_token
        // 2. 调用 /cgi-bin/department/list 获取部门列表
        // 3. 调用 /cgi-bin/user/list 获取成员列表
        // 4. 同步到本地用户/部门表
        return ResponseEntity.ok(Map.of("code", 0, "message", "sync initiated"));
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
            String msgUrl = "https://qyapi.weixin.qq.com/cgi-bin/message/send" +
                    "?access_token=" + accessToken;
            Map<String, Object> payload = Map.of(
                    "touser", toUser,
                    "msgtype", "text",
                    "text", Map.of("content", content),
                    "agentid", weComAppService.getAgentId()
            );
            String resp = restTemplate.postForObject(msgUrl, payload, String.class);
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