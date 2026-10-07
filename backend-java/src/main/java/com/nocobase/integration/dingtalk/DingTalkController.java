package com.nocobase.integration.dingtalk;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.integration.common.ExternalMessageLogEntity;
import com.nocobase.integration.common.ExternalMessageLogRepository;
import com.nocobase.integration.dingtalk.DingTalkApprovalService;
import com.nocobase.integration.dingtalk.DingTalkOrgSyncService;
import com.nocobase.integration.dingtalk.UserMappingService;
import com.nocobase.tenant.TenantContext;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final DingTalkApprovalService approvalService;
    private final DingTalkOrgSyncService orgSyncService;
    private final UserMappingService userMappingService;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private static final Logger log = LoggerFactory.getLogger(DingTalkController.class);

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    /**
     * 事件幂等记录（复用入站消息那张带 UNIQUE 约束的表）。
     * 字段注入而非构造器注入：保持构造签名稳定，不影响既有测试。
     */
    @Autowired
    private ExternalMessageLogRepository externalMessageLogRepository;

    public DingTalkController(DingTalkAdapter adapter,
                              DingTalkAppService appService,
                              DingTalkApprovalService approvalService,
                              DingTalkOrgSyncService orgSyncService,
                              UserMappingService userMappingService,
                              WorkflowInstanceRepository instanceRepository,
                              WorkflowTaskRepository taskRepository,
                              JwtService jwtService,
                              RefreshTokenService refreshTokenService,
                              AuditService auditService) {
        this.adapter = adapter;
        this.appService = appService;
        this.approvalService = approvalService;
        this.orgSyncService = orgSyncService;
        this.userMappingService = userMappingService;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
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
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("code", 0, "message", "success", "data", appService.getAppConfig());
    }

    /**
     * 获取钉钉用户信息。
     */
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

    /**
     * 查询钉钉审批实例状态。
     */
    @GetMapping("/approval-status")
    public Map<String, Object> getApprovalStatus(@RequestParam String instanceId) {
        String status = approvalService.getApprovalStatus(instanceId);
        return Map.of("code", 0, "message", "success",
                "data", Map.of("instanceId", instanceId, "status", status != null ? status : "unknown"));
    }

    /**
     * 提交钉钉 OA 审批表单。
     */
    @PostMapping("/approval")
    public Map<String, Object> createApproval(@RequestBody Map<String, Object> body) {
        String processCode = (String) body.get("processCode");
        String title = (String) body.get("title");
        @SuppressWarnings("unchecked")
        Map<String, Object> formValues = (Map<String, Object>) body.get("formValues");
        String instanceId = approvalService.createApproval(processCode, title, formValues);
        if (instanceId != null) {
            auditService.log(TenantContext.currentTenantId(), "system", "system",
                    "dingtalk.approval.create", "dingtalk_approval", instanceId,
                    Map.of("processCode", processCode, "title", title, "instanceId", instanceId));
            return Map.of("code", 0, "message", "success",
                    "data", Map.of("instanceId", instanceId));
        }
        auditService.log(TenantContext.currentTenantId(), "system", "system",
                "dingtalk.approval.failed", "dingtalk_approval", "unknown",
                Map.of("processCode", processCode, "title", title, "reason", "creation_failed"));
        return Map.of("code", 502, "message", "创建审批实例失败", "data", Map.of());
    }

    /**
     * 钉钉审批回调 — 更新工作流节点状态。
     * 
     * <b>安全修复</b>：
     * 1. 增加 HMAC-SHA256 签名校验（基于 dingtalk.app-secret + timestamp）
     * 2. 删除 @RequestParam tenantId（租户必须由配置推导，绝不允许请求方指定）
     */
    @PostMapping("/approval-callback")
    public ResponseEntity<Map<String, Object>> approvalCallback(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-DingTalk-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-DingTalk-Signature", required = false) String signature
    ) {
        // 1. 签名校验（防止伪造回调）
        if (timestamp == null || signature == null) {
            log.warn("[DingTalk] 审批回调缺少签名头");
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "missing signature headers"));
        }
        
        if (!verifySignature(timestamp, signature, payload)) {
            log.warn("[DingTalk] 审批回调签名验证失败");
            return ResponseEntity.status(401).body(Map.of("code", 401, "message", "invalid signature"));
        }
        
        // 2. 处理业务逻辑
        Object instanceId = payload.get("instance_id");
        Object result = payload.get("result");
        if (instanceId != null && result != null) {
            approvalService.handleApprovalCallback(String.valueOf(instanceId), String.valueOf(result));
            updateWorkflowTaskStatus(String.valueOf(instanceId), String.valueOf(result));
        }
        return ResponseEntity.ok(Map.of("code", 0, "message", "received"));
    }
    
    /**
     * 验证钉钉回调签名。
     * sign = Base64(HmacSHA256(appSecret, timestamp))
     */
    private boolean verifySignature(String timestamp, String signature, Map<String, Object> payload) {
        try {
            String appSecret = appService.getAppSecret();
            if (appSecret == null || appSecret.isBlank()) {
                log.warn("[DingTalk] 审批回调：appSecret 未配置");
                return false;
            }
            
            // 检查时间戳有效性（5 分钟窗口）
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                log.warn("[DingTalk] 审批回调：timestamp 格式错误");
                return false;
            }
            
            long now = System.currentTimeMillis();
            if (Math.abs(now - ts) > 300_000) {
                log.warn("[DingTalk] 审批回调：timestamp 过期 {} vs {}", ts, now);
                return false;
            }
            
            // 计算签名：HmacSHA256(appSecret, timestamp)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8));
            String expectedSig = Base64.getEncoder().encodeToString(digest);
            
            // 常量时间比较防时序攻击
            return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSig.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("[DingTalk] 审批回调签名验证异常", e);
            return false;
        }
    }

    /**
     * 同步钉钉组织架构到本地。
     */
    @PostMapping("/sync-org")
    public Map<String, Object> syncOrg(@RequestBody(required = false) Map<String, Object> body) {
        String tenantId = body == null ? "tenant_default"
                : String.valueOf(body.getOrDefault("tenantId", "tenant_default"));
        return adapter.syncOrganization(tenantId);
    }

    /**
     * 获取用户映射列表。
     */
    @GetMapping("/user-mappings")
    public Map<String, Object> getUserMappings(
            @RequestParam(required = false, defaultValue = "tenant_default") String tenantId
    ) {
        return Map.of("code", 0, "message", "success",
                "data", Map.of("mappings", userMappingService.getMappings(tenantId)));
    }

    /**
     * 获取同步状态。
     */
    @GetMapping("/sync-status")
    public Map<String, Object> getSyncStatus() {
        return Map.of("code", 0, "message", "success",
                "data", Map.of(
                        "lastSyncTime", System.currentTimeMillis(),
                        "syncEnabled", true,
                        "cron", "0 0 * * * ?"
                ));
    }

    /**
     * 钉钉事件回调 — 处理审批状态变更、通讯录变更等事件。
     *
     * <b>安全要求</b>：
     * 1. HMAC-SHA256 + Base64 签名校验（基于 appSecret + timestamp）
     * 2. 按事件 ID 幂等（钉钉会重投）
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> eventCallback(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-DingTalk-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-DingTalk-Signature", required = false) String signature
    ) {
        // 1. 签名校验
        // 注意：验签失败必须返回 **HTTP 401**，而不是「HTTP 200 + 业务码 401」。
        // 后者会让第三方/网关误判为成功，也不会触发钉钉的失败重投。
        if (timestamp == null || signature == null) {
            log.warn("[DingTalk] 事件回调缺少签名头");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "missing signature headers"));
        }

        if (!verifyEventSignature(timestamp, signature)) {
            log.warn("[DingTalk] 事件回调签名验证失败");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "invalid signature"));
        }

        // 2. 解析事件
        Object eventType = payload.get("eventType");
        Object eventId = payload.get("eventId");
        String tenantId = "tenant_default";

        log.info("[DingTalk] 事件回调: type={}, id={}, tenant={}", eventType, eventId, tenantId);

        // 3. 幂等检查（基于 eventId）
        if (eventId != null && !String.valueOf(eventId).isBlank()) {
            if (isDuplicateEvent(String.valueOf(eventId))) {
                log.info("[DingTalk] 检测到重复事件，跳过: id={}", eventId);
                return ResponseEntity.ok(Map.of("code", 0, "message", "duplicate ignored"));
            }
        }

        // 4. 处理事件
        switch (String.valueOf(eventType)) {
            case "approval_status_changed" -> handleApprovalStatusChanged(payload, tenantId);
            case "contact_updated" -> handleContactUpdated(payload, tenantId);
            default -> log.warn("[DingTalk] 未知事件类型: {}", eventType);
        }

        // 5. 记录已处理
        if (eventId != null && !String.valueOf(eventId).isBlank()) {
            markEventProcessed(String.valueOf(eventId), tenantId);
        }

        return ResponseEntity.ok(
                Map.of("code", 0, "message", "success", "data", Map.of("processed", true)));
    }
    
    /**
     * 验证钉钉事件签名（与审批回调签名算法一致）。
     */
    private boolean verifyEventSignature(String timestamp, String signature) {
        try {
            String appSecret = appService.getAppSecret();
            if (appSecret == null || appSecret.isBlank()) {
                log.warn("[DingTalk] 事件回调：appSecret 未配置");
                return false;
            }
            
            // 检查时间戳有效性（5 分钟窗口）
            long ts;
            try {
                ts = Long.parseLong(timestamp);
            } catch (NumberFormatException e) {
                log.warn("[DingTalk] 事件回调：timestamp 格式错误");
                return false;
            }
            
            long now = System.currentTimeMillis();
            if (Math.abs(now - ts) > 300_000) {
                log.warn("[DingTalk] 事件回调：timestamp 过期 {} vs {}", ts, now);
                return false;
            }
            
            // 计算签名：HmacSHA256(appSecret, timestamp)
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8));
            String expectedSig = Base64.getEncoder().encodeToString(digest);
            
            // 常量时间比较防时序攻击
            return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSig.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("[DingTalk] 事件回调签名验证异常", e);
            return false;
        }
    }
    
    /**
     * 幂等检查：查已处理事件表（UNIQUE(source, external_message_id) 兜底并发）。
     *
     * <p>修复前这里是 {@code return false;} 的空壳（TODO 未实现），
     * 导致钉钉重投时事件被**重复处理**（审批状态被反复更新）。
     */
    private boolean isDuplicateEvent(String externalMessageId) {
        return externalMessageLogRepository
                .findBySourceAndExternalMessageId("dingtalk", externalMessageId)
                .isPresent();
    }

    /**
     * 标记事件已处理。修复前是空方法。
     */
    private void markEventProcessed(String externalMessageId, String tenantId) {
        try {
            externalMessageLogRepository.save(
                    new ExternalMessageLogEntity("dingtalk", externalMessageId, tenantId));
        } catch (Exception e) {
            // 并发下 UNIQUE 约束冲突：说明已被其它实例/线程处理，忽略即可
            log.warn("[DingTalk] 记录事件处理标记失败(可能并发重复): id={}, err={}",
                    externalMessageId, e.getMessage());
        }
    }
    
    /**
     * 处理审批状态变更事件。
     */
    private void handleApprovalStatusChanged(Map<String, Object> payload, String tenantId) {
        Object instanceId = payload.get("instance_id");
        Object result = payload.get("result");
        log.info("[DingTalk] 审批状态变更: instance={}, result={}", instanceId, result);
        
        if (instanceId != null && result != null) {
            approvalService.handleApprovalCallback(String.valueOf(instanceId), String.valueOf(result));
            updateWorkflowTaskStatus(String.valueOf(instanceId), String.valueOf(result));
        }
    }
    
    /**
     * 处理通讯录变更事件。
     */
    private void handleContactUpdated(Map<String, Object> payload, String tenantId) {
        Object userId = payload.get("user_id");
        log.info("[DingTalk] 通讯录变更: user={}", userId);
        // TODO: 触发用户同步或更新逻辑
    }

    /**
     * 更新工作流任务状态（钉钉审批回调后调用）。
     */
    private void updateWorkflowTaskStatus(String instanceId, String result) {
        try {
            List<WorkflowTaskEntity> pendingTasks = taskRepository.findByInstanceIdAndStatus(
                    UUID.fromString(instanceId), WorkflowTaskEntity.Status.PENDING);
            for (WorkflowTaskEntity task : pendingTasks) {
                task.setStatus("agree".equalsIgnoreCase(result)
                        ? WorkflowTaskEntity.Status.APPROVED
                        : WorkflowTaskEntity.Status.REJECTED);
                task.setFinishedAt(java.time.Instant.now());
                task.setComment("钉钉审批: " + result);
                taskRepository.save(task);
            }
        } catch (Exception e) {
            log.error("[dingtalk] 更新工作流任务状态失败 instanceId={}: {}", instanceId, e.getMessage(), e);
        }
    }
}
