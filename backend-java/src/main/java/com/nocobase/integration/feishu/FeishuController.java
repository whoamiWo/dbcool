package com.nocobase.integration.feishu;

import com.nocobase.audit.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;

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
    private final FeishuOrgSyncService orgSyncService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FeishuController(FeishuAppService feishuAppService,
                            FeishuOrgSyncService orgSyncService,
                            AuditService auditService) {
        this.feishuAppService = feishuAppService;
        this.orgSyncService = orgSyncService;
        this.auditService = auditService;
    }

    /** 获取飞书 OAuth2 授权地址 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/auth-url")
    public ResponseEntity<Map<String, String>> getAuthUrl() {
        String authUrl = feishuAppService.getOAuthUrl();
        return ResponseEntity.ok(Map.of("authUrl", authUrl));
    }

    /** OAuth2 回调：code 换取 token 并保存配置 */
    @PreAuthorize("isAuthenticated()")
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
            
            // F8：审计租户取自事件体 tenant_key；无法解析时记 unknown，
            // 不得冒充 tenant_default（否则跨租户事件会污染默认租户审计）。
            String eventTenant = resolveEventTenant(json);

            if ("url_verification".equals(type)) {
                String challenge = json.path("challenge").asText();
                if (challenge.isBlank()) {
                    return ResponseEntity.badRequest().body("{\"error\":\"missing challenge\"}");
                }
                auditService.log(eventTenant, "system", "system",
                        "feishu.events.challenge", "feishu_event", challenge,
                        Map.of("eventType", "url_verification", "challenge", challenge));
                return ResponseEntity.ok(challenge);
            }

            if (!feishuAppService.verifySignature(timestamp, nonce, signature, body)) {
                auditService.log(eventTenant, "unknown", "unknown",
                        "feishu.events.rejected", "feishu_event", "unknown",
                        Map.of("timestamp", timestamp, "nonce", nonce, "reason", "signature_invalid"));
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid signature\"}");
            }

            auditService.log(eventTenant, "system", "system",
                    "feishu.events.received", "feishu_event", json.path("event_id").asText("unknown"),
                    Map.of("type", type, "bodyPreview", body.length() > 100 ? body.substring(0, 100) : body));

            feishuAppService.handleEvent(json);
            
            return ResponseEntity.ok("{\"code\":0}");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":\"processing failed\"}");
        }
    }

    /**
     * 同步飞书组织架构到本地（管理员）。
     *
     * <p>租户一律取自 JWT，拒绝 body 中的 tenantId 跨租户触发同步。
     */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/sync-org")
    public Map<String, Object> syncOrg(
            @RequestParam(required = false, defaultValue = "false") boolean dryRun,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Map<String, Object> result = orgSyncService.syncOrganization(user.tenantId(), dryRun, force);
        auditService.log(user.tenantId(), user.userId().toString(), user.username(),
                "feishu.sync.org", "feishu_org_sync", user.tenantId(),
                Map.of("code", String.valueOf(result.get("code")),
                        "dryRun", dryRun, "force", force));
        return result;
    }

    /** 事件体租户：优先 header.tenant_key（v2 事件），兼容顶层 tenant_key。 */
    static String resolveEventTenant(JsonNode json) {
        String key = json.path("header").path("tenant_key").asText("");
        if (key.isBlank()) key = json.path("tenant_key").asText("");
        return key.isBlank() ? "unknown" : key;
    }

    /** 最近一次飞书组织同步状态（管理员，仅限本租户）。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @GetMapping("/sync-status")
    public ResponseEntity<Map<String, Object>> syncStatus(
            @RequestParam(required = false) String tenantId,
            @AuthenticationPrincipal AuthenticatedUser user) {
        if (tenantId != null && !tenantId.isBlank() && !tenantId.equals(user.tenantId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "code", 403, "message", "不得查询其他租户的同步状态", "data", Map.of()));
        }
        boolean enabled = orgSyncService.isSyncEnabled();
        boolean configured = feishuAppService.isConfigured(user.tenantId());
        Map<String, Object> last = orgSyncService.getLastSyncResult(user.tenantId());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("syncEnabled", enabled);
        data.put("configured", configured);
        data.put("lastSyncTime", last == null ? null : last.get("timestamp"));
        data.put("departmentsSynced", last == null ? 0 : last.getOrDefault("departmentsSynced", 0));
        data.put("usersSynced", last == null ? 0 : last.getOrDefault("usersSynced", 0));
        data.put("usersFailed", last == null ? 0 : last.getOrDefault("usersFailed", 0));
        data.put("usersResigned", last == null ? 0 : last.getOrDefault("usersResigned", 0));
        data.put("errors", last == null ? java.util.List.of() : last.getOrDefault("errors", java.util.List.of()));
        data.put("notes", enabled
                ? "手动触发: POST /api/feishu/sync-org"
                : "feishu.sync.enabled=false，同步接口不会发起外部请求");
        int failed = (int) data.get("usersFailed");
        return ResponseEntity.ok(Map.of("code", failed > 0 ? 1 : 0,
                "message", "success", "data", data));
    }

    /** 发送消息到飞书用户/群聊 */
    @PreAuthorize("isAuthenticated()")
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
    @PreAuthorize("isAuthenticated()")
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
