package com.nocobase.integration.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * Slack 集成 REST API。
 *
 * <p>提供：
 * <ul>
 *   <li>OAuth2 授权（安装应用）</li>
 *   <li>Events API 回调（事件订阅 + 挑战响应）</li>
 *   <li>消息发送（chat.postMessage）</li>
 * </ul>
 */
@RestController
@Tag(name = "Slack Integration", description = "Slack 集成")
@RequestMapping("/api/slack")
public class SlackController {

    private final SlackAppService slackAppService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SlackController(SlackAppService slackAppService, RestTemplate restTemplate) {
        this.slackAppService = slackAppService;
        this.restTemplate = restTemplate;
    }

    /** 获取 Slack OAuth2 授权地址 */
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl() {
        String authUrl = slackAppService.getOAuthUrl();
        return ResponseEntity.ok(Map.of("authUrl", authUrl));
    }

    /** OAuth2 回调：code 换取 bot token 并保存配置 */
    @PostMapping("/oauth/callback")
    public ResponseEntity<Map<String, Object>> oauthCallback(
            @RequestParam String code,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "code 必填"));
        }
        try {
            Map<String, Object> result = slackAppService.handleOAuthCallback(code);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "Slack 授权失败：" + e.getMessage()
            ));
        }
    }

    /** Events API 回调：挑战验证 + 事件处理 */
    @PostMapping("/events")
    public ResponseEntity<String> eventsCallback(
            @RequestBody(required = false) String body,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature
    ) {
        // 挑战验证（challenge 响应必须在 3 秒内返回）
        if (body == null || body.isBlank()) {
            return ResponseEntity.badRequest().body("{\"error\":\"empty body\"}");
        }

        try {
            JsonNode json = objectMapper.readTree(body);
            String type = json.path("type").asText();
            
            // Challenge 验证：先校验签名再返回 challenge
            if ("url_verification".equals(type)) {
                // timestamp/signature 缺失时拒绝挑战
                if (timestamp == null || signature == null) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"missing headers\"}");
                }
                // 校验签名（即使 challenge 也需要防中间人）
                if (!verifySignature(timestamp, signature, body)) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid signature\"}");
                }
                String challenge = json.path("challenge").asText();
                if (challenge.isBlank()) {
                    return ResponseEntity.badRequest().body("{\"error\":\"missing challenge\"}");
                }
                return ResponseEntity.ok(challenge);
            }

            // 事件验证：签名校验
            if (timestamp == null || signature == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"missing headers\"}");
            }

            if (!verifySignature(timestamp, signature, body)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid signature\"}");
            }

            // 事件处理（异步执行）
            slackAppService.handleEvent(json);
            
            // 立即返回 200（Slack 要求快速响应）
            return ResponseEntity.ok("{}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":\"processing failed\"}");
        }
    }

    /** Slack 签名验证 */
    private boolean verifySignature(String timestamp, String signature, String body) {
        try {
            String signingSecret = slackAppService.getSigningSecret();
            if (signingSecret == null || signingSecret.isBlank()) {
                return false;
            }

            // 检查时间戳有效性（5 分钟内）
            long ts = Long.parseLong(timestamp);
            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - ts) > 300) {
                return false;
            }

            // 构建签名基：v0:timestamp:body
            String sigBase = "v0:" + timestamp + ":" + body;
            
            // HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(sigBase.getBytes(StandardCharsets.UTF_8));
            String expectedSig = "v0=" + HexFormat.of().formatHex(digest);

            return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSig.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** 发送消息到 Slack 频道 */
    @PostMapping("/message/send")
    public ResponseEntity<Map<String, Object>> sendMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String channel = (String) body.get("channel");
        String text = (String) body.get("text");
        String threadTs = (String) body.get("thread_ts");

        if (channel == null || text == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "channel 和 text 必填"));
        }
        if (!slackAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "Slack 应用未配置"));
        }
        try {
            // ✅ 用 HashMap 动态组装 payload，thread_ts 条件写入
            Map<String, Object> payload = new HashMap<>();
            payload.put("channel", channel);
            payload.put("text", text);
            payload.put("mrkdwn", true);
            if (threadTs != null && !threadTs.isBlank()) {
                payload.put("thread_ts", threadTs);
            }

            // ✅ Authorization header 必须设置！关键 bug 修复
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(slackAppService.getBotToken());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            JsonNode resp = restTemplate.postForObject(
                    "https://slack.com/api/chat.postMessage",
                    entity,
                    JsonNode.class
            );

            if (resp == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "code", 1, "message", "Slack API 无响应"));
            }

            boolean ok = resp.path("ok").asBoolean(false);
            if (!ok) {
                String error = resp.path("error").asText("unknown");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "code", 1, "message", "Slack 发送失败：" + error, "error", error));
            }

            String ts = resp.path("ts").asText("");
            return ResponseEntity.ok(Map.of("code", 0, "message", "sent", "ts", ts));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "Slack 发送异常：" + e.getMessage()));
        }
    }

    /** 回复线程消息 */
    @PostMapping("/message/reply")
    public ResponseEntity<Map<String, Object>> replyMessage(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String channel = (String) body.get("channel");
        String threadTs = (String) body.get("thread_ts");
        String text = (String) body.get("text");

        if (channel == null || threadTs == null || text == null) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", "channel/thread_ts/text 必填"));
        }
        if (!slackAppService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "code", 1, "message", "Slack 应用未配置"));
        }
        try {
            // ✅ 用 HashMap 动态组装 payload，Authorization header 由 HttpEntity 统一注入
            Map<String, Object> payload = new HashMap<>();
            payload.put("channel", channel);
            payload.put("text", text);
            payload.put("thread_ts", threadTs);
            payload.put("mrkdwn", true);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(slackAppService.getBotToken());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            JsonNode resp = restTemplate.postForObject(
                    "https://slack.com/api/chat.postMessage",
                    entity,
                    JsonNode.class
            );

            if (resp == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "code", 1, "message", "Slack API 无响应"));
            }

            boolean ok = resp.path("ok").asBoolean(false);
            if (!ok) {
                String error = resp.path("error").asText("unknown");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                        "code", 1, "message", "Slack 发送失败：" + error, "error", error));
            }

            String ts = resp.path("ts").asText("");
            return ResponseEntity.ok(Map.of("code", 0, "message", "sent", "ts", ts));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "code", 1,
                    "message", "Slack 发送异常：" + e.getMessage()));
        }
    }
}
