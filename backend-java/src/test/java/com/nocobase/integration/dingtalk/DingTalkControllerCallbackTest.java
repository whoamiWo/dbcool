package com.nocobase.integration.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.integration.common.ExternalMessageLogEntity;
import com.nocobase.integration.common.ExternalMessageLogRepository;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import com.nocobase.workflow.WorkflowTaskEntity;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Base64;

/**
 * DingTalkController 回调端点测试（PHASE108 覆盖率冲 85%）。
 *
 * <p>覆盖 approvalCallback / eventCallback 的 HMAC 签名校验 + 幂等逻辑 +
 * 工作流任务状态更新分支。
 */
class DingTalkControllerCallbackTest {

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
    private ExternalMessageLogRepository messageLogRepo;
    private DingTalkController controller;

    private static final String SECRET = "test-secret";

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
        messageLogRepo = mock(ExternalMessageLogRepository.class);

        when(appService.getAppSecret()).thenReturn(SECRET);
        when(messageLogRepo.findBySourceAndExternalMessageId(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(messageLogRepo.save(any(ExternalMessageLogEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.findByInstanceIdAndStatus(any(UUID.class), any()))
                .thenReturn(List.of());

        controller = new DingTalkController(adapter, appService, approvalService, orgSyncService,
                userMappingService, instanceRepository, taskRepository,
                jwtService, refreshTokenService, auditService);
        injectMessageLogRepo(messageLogRepo);
    }

    private void injectMessageLogRepo(ExternalMessageLogRepository repo) {
        try {
            Field f = DingTalkController.class.getDeclaredField("externalMessageLogRepository");
            f.setAccessible(true);
            f.set(controller, repo);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 生成与 controller 相同算法的 HMAC-SHA256 签名。 */
    private static String sign(String secret, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String nowTs() {
        return String.valueOf(System.currentTimeMillis());
    }

    @Test
    void approvalCallback_missingHeaders_returns401() {
        ResponseEntity<Map<String, Object>> r =
                controller.approvalCallback(Map.of("instance_id", "i1"), null, null);

        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void approvalCallback_invalidSignature_returns401() throws Exception {
        String ts = nowTs();
        ResponseEntity<Map<String, Object>> r =
                controller.approvalCallback(Map.of("instance_id", "i1"), ts, "wrong-sig");

        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void approvalCallback_validSignature_processesAndUpdatesWorkflow() throws Exception {
        UUID instanceId = UUID.randomUUID();
        String ts = nowTs();
        String sig = sign(SECRET, ts);

        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setStatus(WorkflowTaskEntity.Status.PENDING);
        when(taskRepository.findByInstanceIdAndStatus(eq(instanceId),
                eq(WorkflowTaskEntity.Status.PENDING)))
                .thenReturn(List.of(task));
        when(taskRepository.save(any(WorkflowTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> r = controller.approvalCallback(
                Map.of("instance_id", instanceId.toString(), "result", "agree"), ts, sig);

        assertEquals(200, r.getStatusCode().value());
        verify(approvalService).handleApprovalCallback(instanceId.toString(), "agree");
        // taskRepository.save 应被调用（workflow task 更新分支）
        verify(taskRepository).save(task);
        assertEquals(WorkflowTaskEntity.Status.APPROVED, task.getStatus());
    }

    @Test
    void approvalCallback_validSigWithRejectedResult_setsRejected() throws Exception {
        UUID instanceId = UUID.randomUUID();
        String ts = nowTs();
        String sig = sign(SECRET, ts);

        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setStatus(WorkflowTaskEntity.Status.PENDING);
        when(taskRepository.findByInstanceIdAndStatus(eq(instanceId),
                eq(WorkflowTaskEntity.Status.PENDING)))
                .thenReturn(List.of(task));
        when(taskRepository.save(any(WorkflowTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        controller.approvalCallback(
                Map.of("instance_id", instanceId.toString(), "result", "reject"), ts, sig);

        assertEquals(WorkflowTaskEntity.Status.REJECTED, task.getStatus());
    }

    @Test
    void eventCallback_missingHeaders_returns401() {
        ResponseEntity<Map<String, Object>> r =
                controller.eventCallback(Map.of("eventType", "x"), null, null);

        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void eventCallback_invalidSignature_returns401() throws Exception {
        ResponseEntity<Map<String, Object>> r =
                controller.eventCallback(Map.of("eventType", "x"), nowTs(), "bad");

        assertEquals(401, r.getStatusCode().value());
    }

    @Test
    void eventCallback_duplicateEvent_returnsDuplicateIgnored() throws Exception {
        when(messageLogRepo.findBySourceAndExternalMessageId(eq("dingtalk"), eq("evt1")))
                .thenReturn(Optional.of(new ExternalMessageLogEntity("dingtalk", "evt1", "t")));
        String ts = nowTs();
        String sig = sign(SECRET, ts);

        ResponseEntity<Map<String, Object>> r = controller.eventCallback(
                Map.of("eventType", "approval_status_changed", "eventId", "evt1"), ts, sig);

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals("duplicate ignored", r.getBody().get("message"));
    }

    @Test
    void eventCallback_contactUpdated_returnsNotImplemented() throws Exception {
        String ts = nowTs();
        String sig = sign(SECRET, ts);

        ResponseEntity<Map<String, Object>> r = controller.eventCallback(
                Map.of("eventType", "contact_updated", "eventId", "evt2", "user_id", "u1"), ts, sig);

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(false, data.get("processed"));
        assertEquals("not_implemented", r.getBody().get("message"));
    }

    @Test
    void eventCallback_unknownType_logsWarningReturnsSuccess() throws Exception {
        String ts = nowTs();
        String sig = sign(SECRET, ts);

        ResponseEntity<Map<String, Object>> r = controller.eventCallback(
                Map.of("eventType", "mystery_event", "eventId", "evt3"), ts, sig);

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(true, data.get("processed"));
        // 标记已处理
        verify(messageLogRepo).save(any(ExternalMessageLogEntity.class));
    }

    @Test
    void eventCallback_approvalStatusChanged_delegatesToService() throws Exception {
        UUID instanceId = UUID.randomUUID();
        String ts = nowTs();
        String sig = sign(SECRET, ts);

        WorkflowTaskEntity task = new WorkflowTaskEntity();
        task.setStatus(WorkflowTaskEntity.Status.PENDING);
        when(taskRepository.findByInstanceIdAndStatus(eq(instanceId),
                eq(WorkflowTaskEntity.Status.PENDING)))
                .thenReturn(List.of(task));
        when(taskRepository.save(any(WorkflowTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> r = controller.eventCallback(
                Map.of("eventType", "approval_status_changed", "eventId", "evt4",
                        "instance_id", instanceId.toString(), "result", "agree"), ts, sig);

        assertEquals(200, r.getStatusCode().value());
        verify(approvalService).handleApprovalCallback(instanceId.toString(), "agree");
        verify(messageLogRepo).save(any(ExternalMessageLogEntity.class));
    }
}
