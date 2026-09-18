package com.nocobase.integration.dingtalk;

import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.integration.dingtalk.DingTalkApprovalService;
import com.nocobase.integration.dingtalk.DingTalkOrgSyncService;
import com.nocobase.integration.dingtalk.UserMappingService;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public DingTalkController(DingTalkAdapter adapter,
                              DingTalkAppService appService,
                              DingTalkApprovalService approvalService,
                              DingTalkOrgSyncService orgSyncService,
                              UserMappingService userMappingService,
                              WorkflowInstanceRepository instanceRepository,
                              WorkflowTaskRepository taskRepository,
                              JwtService jwtService,
                              RefreshTokenService refreshTokenService) {
        this.adapter = adapter;
        this.appService = appService;
        this.approvalService = approvalService;
        this.orgSyncService = orgSyncService;
        this.userMappingService = userMappingService;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    /** 生成扫码授权地址。 */
    @GetMapping("/auth-url")
    public Map<String, Object> authUrl(
            @RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "tenant_default") String tenantId
    ) {
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
            return ResponseEntity.status(rc != null && rc == 500 ? 500 : 401).body(result);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        String userId = String.valueOf(data.get("userId"));
        String username = String.valueOf(data.get("username"));

        String accessToken = jwtService.issueAccessToken(
                UUID.fromString(userId), username, tenantId);
        String refreshToken = refreshTokenService.issue(UUID.fromString(userId));

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
    public Map<String, Object> logout() {
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
            return Map.of("code", 0, "message", "success",
                    "data", Map.of("instanceId", instanceId));
        }
        return Map.of("code", 502, "message", "创建审批实例失败", "data", Map.of());
    }

    /**
     * 钉钉审批回调 — 更新工作流节点状态。
     */
    @PostMapping("/approval-callback")
    public Map<String, Object> approvalCallback(
            @RequestBody Map<String, Object> payload,
            @RequestParam(required = false, defaultValue = "tenant_default") String tenantId
    ) {
        Object instanceId = payload.get("instance_id");
        Object result = payload.get("result");
        if (instanceId != null && result != null) {
            approvalService.handleApprovalCallback(String.valueOf(instanceId), String.valueOf(result));
            updateWorkflowTaskStatus(String.valueOf(instanceId), String.valueOf(result));
        }
        return Map.of("code", 0, "message", "received");
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
            // 记录日志，不阻塞回调响应
        }
    }
}
