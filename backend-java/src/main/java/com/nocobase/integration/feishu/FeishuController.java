package com.nocobase.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;

import java.util.Map;

/**
 * 飞书集成 REST API。
 *
 * <p>提供：
 * <ul>
 *   <li>OAuth2 授权（获取 tenant_access_token）</li>
 *   <li>事件回调（消息接收）</li>
 *   <li>消息发送（im/v1/messages）</li>
 * </ul>
 */
@RestController
@Tag(name = "Feishu Integration", description = "飞书集成")
@RequestMapping("/api/feishu")
public class FeishuController {

    private final FeishuAppService feishuAppService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FeishuController(FeishuAppService feishuAppService) {
        this.feishuAppService = feishuAppService;
    }

    /** 获取飞书 OAuth2 授权地址 */
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl() {
        String authUrl = feishuAppService.getOAuthUrl();
        return ResponseEntity.ok(Map.of("authUrl", authUrl));
    }

    /** OAuth2 回调：code 换取 token 并保存配置 */
    @PostMapping("/oauth/callback")
    public ResponseEntity<Map<String, Object>> oauthCallback(
            @RequestParam String code,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "code 必填"));
        }
        try {
            Map<String, Object> result = feishuAppService.handleOAuthCallback(code);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "飞书授权失败：" + e.getMessage()
            ));
        }
    }

    /** 事件回调：验证 + 事件处理 */
    @PostMapping("/events")
    public ResponseEntity<String> eventsCallback(
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature
    ) {
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest().body("{\"error\":\"empty body\"}");
        }

        try {
            JsonNode json = objectMapper.readTree(body);
            String type = json.path("type").asText();
            
            // Challenge 验证
            if ("url_verification".equals(type)) {
                String challenge = json.path("challenge").asText();
                if (challenge.isBlank()) {
                    return ResponseEntity.badRequest().body("{\"error\":\"missing challenge\"}");
                }
                return ResponseEntity.ok(challenge);
            }

            // 签名验证：飞书服务端用 SHA256(timestamp+nonce+encrypt_key+body) 算签名
            if (!feishuAppService.verifySignature(timestamp, nonce, signature, body)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid signature\"}");
            }

            // 事件处理（异步）
            feishuAppService.handleEvent(json);
            
            return ResponseEntity.ok("{\"code\":0}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":\"processing failed\"}");
        }
    }

    /** 发送消息到飞书用户/群聊 */
    @PostMapping("/message/send")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String openId = (String) body.get("open_id");
        String text = (String) body.get("text");
        
        if (openId == null || text == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "open_id 和 text 必填"));
        }
        if (!feishuAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "飞书应用未配置"));
        }
        try {
            Map<String, Object> result = feishuAppService.sendMessage(openId, text);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "飞书发送异常：" + e.getMessage()));
        }
    }

    /** 回复线程消息（飞书支持 reply_to_message_id） */
    @PostMapping("/message/reply")
    public ResponseEntity<Map<String, Object>> replyMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String openId = (String) body.get("open_id");
        String replyToMsgId = (String) body.get("reply_to_message_id");
        String text = (String) body.get("text");
        
        if (openId == null || replyToMsgId == null || text == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "open_id/reply_to_message_id/text 必填"));
        }
        if (!feishuAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "飞书应用未配置"));
        }
        try {
            Map<String, Object> result = feishuAppService.replyMessage(openId, replyToMsgId, text);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "飞书发送异常：" + e.getMessage()));
        }
    }
}
