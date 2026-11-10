package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nocobase.approval.ApprovalCallbackEventService;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 钉钉审批加密回调入口测试：控制器只校验入口参数并委托统一消费服务。 */
class DingTalkApprovalCallbackTest {

    private static final String CIPHERTEXT = "encrypted-callback";

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
                mock(com.nocobase.audit.AuditService.class));
    }

    @Test
    void missingEncryptionParameters_rejectedWith401() {
        assertThatThrownBy(() -> controller.approvalCallback(
                Map.of("encrypt", CIPHERTEXT), null, "timestamp", "nonce"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.approvalCallback(
                Map.of("encrypt", CIPHERTEXT), "signature", null, "nonce"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.approvalCallback(
                Map.of("encrypt", CIPHERTEXT), "signature", "timestamp", null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> controller.approvalCallback(
                Map.of(), "signature", "timestamp", "nonce"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(callbackEventService, instanceRepository, taskRepository);
    }

    @Test
    void encryptedCallback_delegatesExactlyOnceToUnifiedConsumer() {
        Map<String, Object> encryptedResponse = Map.of("msg_signature", "response-signature");
        when(callbackEventService.receive("signature", "timestamp", "nonce", CIPHERTEXT))
                .thenReturn(encryptedResponse);

        Map<String, Object> response = controller.approvalCallback(
                Map.of("encrypt", CIPHERTEXT), "signature", "timestamp", "nonce");

        assertThat(response).isSameAs(encryptedResponse);
        verify(callbackEventService).receive("signature", "timestamp", "nonce", CIPHERTEXT);
        verifyNoInteractions(instanceRepository, taskRepository);
    }

    @Test
    void legacyEventsEntry_isGoneWithoutSecondConsumption() {
        var response = controller.legacyEventCallback();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsEntry("code", 410);
        verifyNoInteractions(callbackEventService, instanceRepository, taskRepository);
    }
}
