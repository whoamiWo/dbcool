package com.nocobase.integration.dingtalk;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;

/**
 * 钉钉嵌入 REST API — 授权地址、免密登录、组织架构同步、审批回调。
 *
 * <p>登录成功后签发与账号密码登录一致的 JWT(access_token + refresh_token),
 * 因此前端拿到令牌后即可调用所有既有 /api 接口,权限体系完全复用。
 */
@RestController
@RequestMapping("/api/dingtalk")
public class DingTalkController {

    private final DingTalkAdapter adapter;
    private final DingTalkAppService appService;
    private final com.nocobase.approval.ApprovalCallbackEventService approvalCallbackEventService;
    private final DingTalkOrgSyncService orgSyncService;
    private final UserMappingService userMappingService;
    private static final Logger log = LoggerFactory.getLogger(DingTalkController.class);

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public DingTalkController(DingTalkAdapter adapter,
                              DingTalkAppService appService,
                               com.nocobase.approval.ApprovalCallbackEventService approvalCallbackEventService,
                              DingTalkOrgSyncService orgSyncService,
                              UserMappingService userMappingService,
                              WorkflowInstanceRepository instanceRepository,
                              WorkflowTaskRepository taskRepository,
                              JwtService jwtService,
                              RefreshTokenService refreshTokenService,
                              AuditService auditService) {
        this.adapter = adapter;
        this.appService = appService;
        this.approvalCallbackEventService = approvalCallbackEventService;
        this.orgSyncService = orgSyncService;
        this.userMappingService = userMappingService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    /** 生成扫码授权地址（支持 GET/POST）。 */
    @GetMapping("/auth-url")
    public Map<String, Object> authUrlGet(
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "tenant_default") String tenantId
    ) {
        return authUrlCommon(state, tenantId);
    }

    @PostMapping("/auth-url")
    public Map<String, Object> authUrlPost(@RequestBody(required = false) Map<String, Object> body) {
        String state = body == null ? null : (String) body.get("state");
        String tenantId = body == null ? null : (String) body.get("tenantId");
        if (tenantId == null || tenantId.isBlank()) tenantId = "tenant_default";
        return authUrlCommon(state, tenantId);
    }

    private Map<String, Object> authUrlCommon(String state, String tenantId) {
        return Map.of("code", 0, "message", "success",
                "data", Map.of("url", appService.getAuthUrl(state, tenantId),
                        "configured", appService.isConfigured()));
    }

    /**
     * 免密登录:body = {code, tenantId}
     *
     * @return data = {access_token, refresh_token, userId, username, nickname}
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String code = body == null ? null : (String) body.get("code");
        String tenantId = body == null ? null : (String) body.getOrDefault("tenantId", "tenant_default");
        if (tenantId == null || tenantId.isBlank()) tenantId = "tenant_default";

        Map<String, Object> result = adapter.ssoLogin(code, tenantId);
        Integer rc = (Integer) result.get("code");
        if (rc == null || rc != 0) {
            auditService.log(tenantId, "anonymous", "anonymous",
                    "dingtalk.login.failed", "dingtalk_auth", "code_" + code,
                    Map.of("tenantId", tenantId, "reason", rc != null && rc == 500 ? "server_error" : "auth_failed"));
            return ResponseEntity.status(rc != null && rc == 500 ? 500 : 401).body(result);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String userId = String.valueOf(data.get("userId"));
        String username = String.valueOf(data.get("username"));

        String accessToken = jwtService.issueAccessToken(
                UUID.fromString(userId), username, tenantId);
        String refreshToken = refreshTokenService.issue(UUID.fromString(userId));

        auditService.log(tenantId, userId, username,
                "dingtalk.login.success", "dingtalk_auth", userId,
                Map.of("userId", userId, "username", username, "tenantId", tenantId));

        return ResponseEntity.ok(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "access_token", accessToken,
                        "refresh_token", refreshToken,
                        "userId", userId,
                        "username", username,
                        "nickname", data.getOrDefault("nickname", ""),
                        "tenantId", tenantId
                )
        ));
    }

    /** 应用配置(运维核对)。 */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("code", 0, "message", "success", "data", appService.getAppConfig());
    }

    /**
     * 获取钉钉用户信息。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/user-info")
    public Map<String, Object> getUserInfo(@RequestParam String accessToken) {
        try {
            String url = "https://oapi.dingtalk.com/sns/getuserinfo?access_token=" + accessToken;
            String response = new org.springframework.web.client.RestTemplate()
                    .getForObject(url, String.class);
            com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response == null ? "{}" : response);
            String nick = json.path("user_info").path("nick").asText("");
            String userId = json.path("user_info").path("userid").asText("");
            return Map.of("code", 0, "message", "success",
                    "data", Map.of("nickname", nick, "userId", userId));
        } catch (Exception e) {
            return Map.of("code", 502, "message", "获取用户信息失败: " + e.getMessage(), "data", Map.of());
        }
    }

    /**
     * 钉钉退出登录。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String userId = "anonymous";
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                var claims = jwtService.parseAccessToken(token);
                if (claims != null) {
                    userId = claims.getSubject();
                }
            } catch (Exception e) {
                userId = "anonymous";
            }
        }
        auditService.log("unknown", userId, "anonymous",
                "dingtalk.logout", "dingtalk_auth", userId,
                Map.of("userId", userId));
        return Map.of("code", 0, "message", "success", "data", Map.of("loggedOut", true));
    }

    /** 裸 OA 创建入口已关闭；必须由审批模板、用户映射和 V83 绑定统一发起。 */

    /** 官方加密 HTTP 回调：验签、check_url、精确绑定与幂等由单一服务完成。 */
    @PostMapping("/approval-callback")
    public Map<String, Object> approvalCallback(
            @RequestBody Map<String, Object> payload,
            @RequestParam(value = "msg_signature", required = false) String msgSignature,
            @RequestParam(value = "timeStamp", required = false) String timeStamp,
            @RequestParam(value = "nonce", required = false) String nonce) {
        Object encrypt = payload == null ? null : payload.get("encrypt");
        if (!(encrypt instanceof String ciphertext) || msgSignature == null || timeStamp == null || nonce == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回调加密参数缺失");
        }
        return approvalCallbackEventService.receive(msgSignature, timeStamp, nonce, ciphertext);
    }

    /**
     * 同步钉钉组织架构到本地。
     *
     * <p>G2：租户优先取自 JWT，避免管理员用 body 指定其它租户；租户未配置凭证时
     * 服务层直接拒绝，且不发起任何外部请求。
     *
     * @param dryRun 只返回待离职名单，不写库
     * @param force  跳过离职比例熔断（仅 ADMIN，审计留痕）
     */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @PostMapping("/sync-org")
    public Map<String, Object> syncOrg(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            @AuthenticationPrincipal AuthenticatedUser user) {
        String tenantId = user != null && user.tenantId() != null && !user.tenantId().isBlank()
                ? user.tenantId()
                : (body == null ? "tenant_default"
                        : String.valueOf(body.getOrDefault("tenantId", "tenant_default")));
        // A-4：此前经 adapter 落到 DingTalkAppService.syncOrganization 的占位实现，
        // 直接返回 code=0 且什么都没同步（又一处假成功）。现走真实组织同步。
        Map<String, Object> result = orgSyncService.syncOrganization(tenantId, dryRun, force);
        auditService.log(tenantId,
                user == null ? "system" : user.userId().toString(),
                user == null ? "system" : user.username(),
                "dingtalk.sync.org", "dingtalk_org_sync", tenantId,
                Map.of("code", String.valueOf(result.get("code")),
                        "dryRun", dryRun, "force", force));
        return result;
    }

    /**
     * 获取用户映射列表。
     */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @GetMapping("/user-mappings")
    public Map<String, Object> getUserMappings(
            @RequestParam(required = false, defaultValue = "tenant_default") String tenantId
    ) {
        return Map.of("code", 0, "message", "success",
                "data", Map.of("mappings", userMappingService.getMappings(tenantId)));
    }

    /**
     * 获取同步状态。
     *
     * <p>PHASE113：此前返回硬编码 {@code lastSyncTime=now} + {@code syncEnabled=true}，
     * 属于静默谎称"已同步"的假实现。现改为：{@code lastSyncTime} 仅在持久化了同步记录
     * 时回填（当前未建同步历史表，故始终为 null），{@code syncEnabled} 反映 app-key /
     * app-secret 实际配置状态；{@code notes} 给出可操作的建议。
     *
     * <p>未来如引入 {@code DingTalkSyncHistoryRepository}，在此处查最近一条即可。
     */
    @PreAuthorize("hasRole(T(com.nocobase.auth.RoleName).ADMIN)")
    @GetMapping("/sync-status")
    public Map<String, Object> getSyncStatus(
            @RequestParam(required = false, defaultValue = "tenant_default") String tenantId) {
        boolean configured = appService.isConfigured(tenantId);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("syncEnabled", configured);
        data.put("cron", "0 0 * * * ?");
        data.put("configured", configured);
        // A-4：返回最近一次同步的真实计数（departmentsSynced/usersSynced/usersFailed/errors），
        // 未同步过时 lastSyncTime 为 null 且计数为 0，不再谎称成功。
        Map<String, Object> last = orgSyncService.getLastSyncResult(tenantId);
        data.put("lastSyncTime", last == null ? null : last.get("timestamp"));
        data.put("departmentsSynced", last == null ? 0 : last.getOrDefault("departmentsSynced", 0));
        data.put("usersSynced", last == null ? 0 : last.getOrDefault("usersSynced", 0));
        data.put("usersFailed", last == null ? 0 : last.getOrDefault("usersFailed", 0));
        data.put("usersResigned", last == null ? 0 : last.getOrDefault("usersResigned", 0));
        data.put("errors", last == null ? java.util.List.of() : last.getOrDefault("errors", java.util.List.of()));
        data.put("notes", configured
                ? "凭证已配置。手动触发: POST /api/dingtalk/sync-org; 定时每小时自动执行。"
                : "未配置钉钉凭证，定时任务不会真正同步。");
        int failed = (int) data.get("usersFailed");
        return Map.of("code", failed > 0 ? 1 : 0, "message", "success", "data", data);
    }

    /** 旧明文事件入口永久停用；审批只走 approval-callback 验签解密与持久幂等。 */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> legacyEventCallback() {
        auditService.log("unknown", "system", "system", "dingtalk.events.legacy_rejected",
                "dingtalk", "events", Map.of("result", "gone"));
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("code", 410, "message", "请使用 /approval-callback 加密回调"));
    }

}
