package com.nocobase.integration.dingtalk;

import com.nocobase.auth.JwtService;
import com.nocobase.audit.AuditService;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PHASE105: 覆盖 DingTalkController.login / logout / createApproval
 *
 * <p>getUserInfo 实际调用 RestTemplate 调外部 HTTP，难以 mock；
 * 该方法本身是 GET 请求，缺口主要在异常分支，交由集成测试覆盖。
 */
class DingTalkControllerAuthAndApprovalTest {

    private DingTalkAdapter adapter;
    private DingTalkAppService appService;
    private DingTalkApprovalService approvalService;
    private DingTalkOrgSyncService syncService;
    private UserMappingService mappingService;
    private WorkflowInstanceRepository workflowInstanceRepo;
    private WorkflowTaskRepository workflowTaskRepo;
    private JwtService jwtService;
    private com.nocobase.auth.RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private DingTalkController controller;

    private static final String TENANT = "tenant_default";

    @BeforeEach
    void setUp() {
        adapter = mock(DingTalkAdapter.class);
        appService = mock(DingTalkAppService.class);
        approvalService = mock(DingTalkApprovalService.class);
        syncService = mock(DingTalkOrgSyncService.class);
        mappingService = mock(UserMappingService.class);
        workflowInstanceRepo = mock(WorkflowInstanceRepository.class);
        workflowTaskRepo = mock(WorkflowTaskRepository.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(com.nocobase.auth.RefreshTokenService.class);
        auditService = mock(AuditService.class);

        when(appService.getAppConfig()).thenReturn(Map.of("appId", "test_id", "appName", "测试"));
        when(jwtService.issueAccessToken(any(UUID.class), anyString(), anyString()))
                .thenReturn("access_token_xyz");
        when(refreshTokenService.issue(any(UUID.class))).thenReturn("refresh_token_abc");

        controller = new DingTalkController(
                adapter, appService, approvalService, syncService, mappingService,
                workflowInstanceRepo, workflowTaskRepo, jwtService,
                refreshTokenService, auditService);
    }

    // ==================== login ====================

    @Test
    void login_missingCode_returns401() {
        // code 为 null，adapter.ssoLogin(null, tenant) 返回非 0 结果
        when(adapter.ssoLogin(isNull(), eq(TENANT)))
                .thenReturn(Map.of("code", 401, "message", "missing code"));

        ResponseEntity<Map<String, Object>> resp = controller.login(Map.of());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("code")).isEqualTo(401);
    }

    @Test
    void login_ssoLoginReturnsError_returns401() {
        when(adapter.ssoLogin("bad_code", TENANT))
                .thenReturn(Map.of("code", 401, "message", "auth failed"));

        ResponseEntity<Map<String, Object>> resp = controller.login(Map.of("code", "bad_code"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().get("code")).isEqualTo(401);
    }

    @Test
    void login_success_returnsTokens() {
        Map<String, Object> ssoResult = Map.of("code", 0,
                "data", Map.of("userId", "3fa85f64-5717-4562-b3fc-2c963f66afa6", "username", "test_user", "nickname", "测试用户"));
        when(adapter.ssoLogin("ok_code", TENANT)).thenReturn(ssoResult);
        when(jwtService.issueAccessToken(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "test_user", TENANT))
                .thenReturn("jwt_access_token");

        ResponseEntity<Map<String, Object>> resp = controller.login(Map.of("code", "ok_code"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("access_token")).isEqualTo("jwt_access_token");
        assertThat(data.get("userId")).isEqualTo("3fa85f64-5717-4562-b3fc-2c963f66afa6");
    }

    // ==================== logout ====================

    @Test
    void logout_missingAuthHeader_returnsSuccess() {
        // authHeader == null → userId = "anonymous", 仍返回 0
        Map<String, Object> resp = controller.logout(null);
        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void logout_invalidToken_returnsSuccess() {
        // parseAccessToken 抛异常 → userId = "anonymous"
        Map<String, Object> resp = controller.logout("Bearer invalid_token");
        assertThat(resp.get("code")).isEqualTo(0);
    }

    @Test
    void logout_validToken_logsOut() {
        io.jsonwebtoken.Claims claims = mock(io.jsonwebtoken.Claims.class);
        when(claims.getSubject()).thenReturn("user_1");
        when(jwtService.parseAccessToken("valid_token")).thenReturn(claims);

        Map<String, Object> resp = controller.logout("Bearer valid_token");

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("message")).isEqualTo("success");
    }

    // ==================== createApproval ====================

    @Test
    void createApproval_serviceReturnsNull_returns502() {
        // processCode/title/formValues 齐全,但 service 返回 null → 502
        when(approvalService.createApproval(eq("proc_x"), eq("t"), isNull()))
                .thenReturn(null);

        Map<String, Object> resp = controller.createApproval(
                Map.of("processCode", "proc_x", "title", "t"));

        assertThat(resp.get("code")).isEqualTo(502);
        assertThat(resp.get("message")).isEqualTo("创建审批实例失败");
    }

    @Test
    void createApproval_approvalServiceReturnsNull_returns502() {
        when(approvalService.createApproval("proc_1", "title", Map.of("a", 1)))
                .thenReturn(null);

        Map<String, Object> resp = controller.createApproval(
                Map.of("processCode", "proc_1", "title", "title", "formValues", Map.of("a", 1)));

        assertThat(resp.get("code")).isEqualTo(502);
    }

    @Test
    void createApproval_success_returnsInstanceId() {
        when(approvalService.createApproval("proc_1", "title", Map.of("a", 1)))
                .thenReturn("inst_123");

        Map<String, Object> resp = controller.createApproval(
                Map.of("processCode", "proc_1", "title", "title", "formValues", Map.of("a", 1)));

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(resp.get("message")).isEqualTo("success");
        assertThat(((Map<?, ?>) resp.get("data")).get("instanceId")).isEqualTo("inst_123");
    }

    // ==================== approvalStatus ====================

    @Test
    void getApprovalStatus_nullStatus_returnsUnknown() {
        when(approvalService.getApprovalStatus("inst_1"))
                .thenReturn(null);

        Map<String, Object> resp = controller.getApprovalStatus("inst_1");

        assertThat(resp.get("code")).isEqualTo(0);
        assertThat(((Map<?, ?>) resp.get("data")).get("status")).isEqualTo("unknown");
    }

    @Test
    void getApprovalStatus_returnsStatus() {
        when(approvalService.getApprovalStatus("inst_2"))
                .thenReturn("APPROVED");

        Map<String, Object> resp = controller.getApprovalStatus("inst_2");

        assertThat(((Map<?, ?>) resp.get("data")).get("status")).isEqualTo("APPROVED");
    }
}