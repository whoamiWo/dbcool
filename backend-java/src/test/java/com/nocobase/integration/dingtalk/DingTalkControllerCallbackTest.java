package com.nocobase.integration.dingtalk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nocobase.approval.ApprovalCallbackEventService;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 钉钉回调的唯一入口测试，不包含旧 HMAC、UUID 猜测或工作流二次消费。 */
class DingTalkControllerCallbackTest {

    private ApprovalCallbackEventService callbackEventService;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private DingTalkController controller;

    @BeforeEach
    void setUp() {
        callbackEventService = mock(ApprovalCallbackEventService.class);
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        controller = new DingTalkController(
                mock(DingTalkAdapter.class),
                mock(DingTalkAppService.class),
                callbackEventService,
                mock(DingTalkOrgSyncService.class),
                mock(UserMappingService.class),
                instanceRepository,
                taskRepository,
                mock(JwtService.class),
                mock(RefreshTokenService.class),
                mock(AuditService.class));
    }

    @Test
    void approvalCallback_missingEncryptedBody_returns401() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.approvalCallback(Map.of(), "signature", "timestamp", "nonce"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verifyNoInteractions(callbackEventService, instanceRepository, taskRepository);
    }

    @Test
    void approvalCallback_missingEncryptionQueryParameters_returns401() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.approvalCallback(Map.of("encrypt", "ciphertext"),
                        "signature", null, "nonce"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verifyNoInteractions(callbackEventService, instanceRepository, taskRepository);
    }

    @Test
    void approvalCallback_delegatesEncryptedPayloadToUnifiedService() {
        Map<String, Object> expected = Map.of("msg_signature", "signed-response");
        when(callbackEventService.receive("signature", "timestamp", "nonce", "ciphertext"))
                .thenReturn(expected);

        Map<String, Object> actual = controller.approvalCallback(
                Map.of("encrypt", "ciphertext"), "signature", "timestamp", "nonce");

        assertSame(expected, actual);
        verify(callbackEventService).receive("signature", "timestamp", "nonce", "ciphertext");
        verifyNoInteractions(instanceRepository, taskRepository);
    }

    @Test
    void legacyEventsEntry_returns410WithoutConsumingApproval() {
        var response = controller.legacyEventCallback();

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertEquals(410, response.getBody().get("code"));
        verifyNoInteractions(callbackEventService, instanceRepository, taskRepository);
    }
}
