package com.nocobase.integration.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.integration.common.ExternalMessageLogRepository;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * DingTalkController 授权/登录/配置端点测试（PHASE108 覆盖率冲 85%）。
 *
 * <p>覆盖 authUrlGet/authUrlPost/login/config 等基础端点。
 */
class DingTalkControllerAuthTest {

    private DingTalkAdapter adapter;
    private DingTalkAppService appService;
    private DingTalkApprovalService approvalService;
    private DingTalkOrgSyncService orgSyncService;
    private UserMappingService userMappingService;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private DingTalkController controller;

    @BeforeEach
    void setUp() {
        adapter = mock(DingTalkAdapter.class);
        appService = mock(DingTalkAppService.class);
        approvalService = mock(DingTalkApprovalService.class);
        orgSyncService = mock(DingTalkOrgSyncService.class);
        userMappingService = mock(UserMappingService.class);
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        auditService = mock(AuditService.class);

        controller = new DingTalkController(adapter, appService, approvalService, orgSyncService,
                userMappingService, instanceRepository, taskRepository,
                jwtService, refreshTokenService, auditService);
    }

    /** 注入 @Autowired 的 externalMessageLogRepository（用于事件幂等测试）。 */
    private void injectMessageLogRepo(ExternalMessageLogRepository repo) {
        try {
            Field f = DingTalkController.class.getDeclaredField("externalMessageLogRepository");
            f.setAccessible(true);
            f.set(controller, repo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void authUrlGet_returnsAuthUrlAndConfiguredFlag() {
        when(appService.getAuthUrl(eq("state1"), eq("tenant1")))
                .thenReturn("https://login.dingtalk.com/oauth2/auth?client_id=k");
        when(appService.isConfigured()).thenReturn(true);

        Map<String, Object> r = controller.authUrlGet("state1", "tenant1");

        assertEquals(0, r.get("code"));
        assertEquals("success", r.get("message"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("https://login.dingtalk.com/oauth2/auth?client_id=k", data.get("url"));
        assertEquals(true, data.get("configured"));
    }

    @Test
    void authUrlPost_nullBody_defaultsToDefaultTenant() {
        when(appService.getAuthUrl(eq(null), eq("tenant_default")))
                .thenReturn("url_default");
        when(appService.isConfigured()).thenReturn(false);

        Map<String, Object> r = controller.authUrlPost(null);

        assertEquals(0, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("url_default", data.get("url"));
        assertEquals(false, data.get("configured"));
    }

    @Test
    void login_success_issuesTokens() {
        UUID userId = UUID.randomUUID();
        when(adapter.ssoLogin(eq("code1"), eq("tenant1")))
                .thenReturn(Map.of("code", 0, "data", Map.of(
                        "userId", userId.toString(),
                        "username", "alice",
                        "nickname", "Alice")));
        when(jwtService.issueAccessToken(eq(userId), eq("alice"), eq("tenant1"))).thenReturn("AT");
        when(refreshTokenService.issue(eq(userId))).thenReturn("RT");

        ResponseEntity<Map<String, Object>> r = controller.login(Map.of("code", "code1", "tenantId", "tenant1"));

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals("AT", data.get("access_token"));
        assertEquals("RT", data.get("refresh_token"));
        assertEquals(userId.toString(), data.get("userId"));
        verify(auditService).log(eq("tenant1"), eq(userId.toString()), eq("alice"),
                eq("dingtalk.login.success"), eq("dingtalk_auth"), eq(userId.toString()), any());
    }

    @Test
    void login_failed_returns401AndAudit() {
        when(adapter.ssoLogin(eq("bad"), eq("tenant1")))
                .thenReturn(Map.of("code", 401, "message", "invalid code"));

        ResponseEntity<Map<String, Object>> r = controller.login(Map.of("code", "bad", "tenantId", "tenant1"));

        assertEquals(401, r.getStatusCode().value());
        verify(auditService).log(eq("tenant1"), eq("anonymous"), eq("anonymous"),
                eq("dingtalk.login.failed"), eq("dingtalk_auth"), anyString(), any());
    }

    @Test
    void login_serverError_returns500() {
        when(adapter.ssoLogin(eq("x"), eq("tenant1")))
                .thenReturn(Map.of("code", 500, "message", "dingtalk down"));

        ResponseEntity<Map<String, Object>> r = controller.login(Map.of("code", "x", "tenantId", "tenant1"));

        assertEquals(500, r.getStatusCode().value());
    }

    @Test
    void config_returnsAppConfig() {
        when(appService.getAppConfig()).thenReturn(Map.of("corpId", "corp1"));

        Map<String, Object> r = controller.config();

        assertEquals(0, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("corp1", data.get("corpId"));
    }

    @Test
    void syncOrg_delegatesToAdapter() {
        when(adapter.syncOrganization("tenant1")).thenReturn(Map.of("code", 0, "synced", 42));

        Map<String, Object> r = controller.syncOrg(Map.of("tenantId", "tenant1"));

        assertEquals(0, r.get("code"));
        verify(adapter).syncOrganization("tenant1");
    }

    @Test
    void getUserMappings_returnsMappings() {
        when(userMappingService.getMappings("tenant1"))
                .thenReturn(List.of(Map.of("openid_a", "user1")));

        Map<String, Object> r = controller.getUserMappings("tenant1");

        assertEquals(0, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals(List.of(Map.of("openid_a", "user1")), data.get("mappings"));
    }

    @Test
    void getSyncStatus_returnsPlaceholder() {
        Map<String, Object> r = controller.getSyncStatus();

        assertEquals(0, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertNotNull(data.get("lastSyncTime"));
        assertEquals(true, data.get("syncEnabled"));
    }

    @Test
    void logout_withValidToken_auditsUserId() {
        UUID userId = UUID.randomUUID();
        io.jsonwebtoken.Claims claims = mock(io.jsonwebtoken.Claims.class);
        when(claims.getSubject()).thenReturn(userId.toString());
        when(jwtService.parseAccessToken("validtoken")).thenReturn(claims);

        Map<String, Object> r = controller.logout("Bearer validtoken");

        assertEquals(0, r.get("code"));
        verify(auditService).log(eq("unknown"), eq(userId.toString()), eq("anonymous"),
                eq("dingtalk.logout"), eq("dingtalk_auth"), eq(userId.toString()), any());
    }

    @Test
    void logout_withMalformedToken_auditsAnonymous() {
        when(jwtService.parseAccessToken("broken")).thenThrow(new RuntimeException("bad token"));

        Map<String, Object> r = controller.logout("Bearer broken");

        assertEquals(0, r.get("code"));
        verify(auditService).log(eq("unknown"), eq("anonymous"), eq("anonymous"),
                eq("dingtalk.logout"), eq("dingtalk_auth"), eq("anonymous"), any());
    }

    @Test
    void logout_withoutHeader_auditsAnonymous() {
        Map<String, Object> r = controller.logout(null);

        assertEquals(0, r.get("code"));
        verify(auditService).log(eq("unknown"), eq("anonymous"), eq("anonymous"),
                eq("dingtalk.logout"), eq("dingtalk_auth"), eq("anonymous"), any());
    }

    @Test
    void getApprovalStatus_nullReturnsUnknown() {
        when(approvalService.getApprovalStatus("inst1")).thenReturn(null);

        Map<String, Object> r = controller.getApprovalStatus("inst1");

        assertEquals(0, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("unknown", data.get("status"));
        assertEquals("inst1", data.get("instanceId"));
    }

    @Test
    void createApproval_success_auditsAndReturns() {
        when(approvalService.createApproval(eq("proc1"), eq("t1"), any()))
                .thenReturn("inst_new");

        Map<String, Object> r = controller.createApproval(
                Map.of("processCode", "proc1", "title", "t1", "formValues", Map.of("k", "v")));

        assertEquals(0, r.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.get("data");
        assertEquals("inst_new", data.get("instanceId"));
        verify(auditService).log(anyString(), eq("system"), eq("system"),
                eq("dingtalk.approval.create"), any(), eq("inst_new"), any());
    }

    @Test
    void createApproval_failed_returns502() {
        when(approvalService.createApproval(eq("proc1"), eq("t1"), any()))
                .thenReturn(null);

        Map<String, Object> r = controller.createApproval(
                Map.of("processCode", "proc1", "title", "t1"));

        assertEquals(502, r.get("code"));
        verify(auditService).log(anyString(), eq("system"), eq("system"),
                eq("dingtalk.approval.failed"), any(), eq("unknown"), any());
    }

}
