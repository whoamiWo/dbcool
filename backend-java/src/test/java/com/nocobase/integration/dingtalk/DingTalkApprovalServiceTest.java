package com.nocobase.integration.dingtalk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PHASE105: DingTalkApprovalService 测试。
 *
 * <p>该 Service 在方法内部 {@code new RestTemplate()}，无法注入 mock，因此覆盖
 * 「未配置短路」「空入参短路」「已配置但网络失败降级（不抛出）」三类可离线验证的路径。
 */
class DingTalkApprovalServiceTest {

    private DingTalkApprovalService service;
    private com.nocobase.workflow.WorkflowInstanceRepository workflowInstanceRepository;
    private com.nocobase.workflow.WorkflowTaskRepository workflowTaskRepository;
    private DingTalkAppService appService;

    @BeforeEach
    void setUp() {
        appService = mock(DingTalkAppService.class);
        when(appService.isConfigured()).thenReturn(true);
        workflowInstanceRepository = mock(com.nocobase.workflow.WorkflowInstanceRepository.class);
        workflowTaskRepository = mock(com.nocobase.workflow.WorkflowTaskRepository.class);
        service = new DingTalkApprovalService(appService, workflowInstanceRepository, workflowTaskRepository);
    }

    // ── 未配置短路 ──────────────────────────────────────────
    @Test
    void createApproval_notConfigured_returnsNull() {
        when(appService.isConfigured()).thenReturn(false);
        assertThat(service.createApproval("proc1", "请假", Map.of())).isNull();
    }

    @Test
    void getApprovalStatus_notConfigured_returnsNull() {
        when(appService.isConfigured()).thenReturn(false);
        assertThat(service.getApprovalStatus("inst123")).isNull();
    }

    @Test
    void cancelApproval_notConfigured_returnsEarly() {
        when(appService.isConfigured()).thenReturn(false);
        assertThatCode(() -> service.cancelApproval("inst123")).doesNotThrowAnyException();
    }

    // ── 空入参短路 ──────────────────────────────────────────
    @Test
    void getApprovalStatus_nullInstanceId_returnsNull() {
        assertThat(service.getApprovalStatus(null)).isNull();
    }

    @Test
    void getApprovalStatus_blankInstanceId_returnsNull() {
        // 空串不是 null，会走 HTTP 分支；离线失败 → null
        assertThat(service.getApprovalStatus("")).isNull();
    }

    @Test
    void cancelApproval_nullInstanceId_returnsEarly() {
        assertThatCode(() -> service.cancelApproval(null)).doesNotThrowAnyException();
    }

    // ── 已配置：网络失败降级（不抛出） ────────────────────────
    @Test
    void createApproval_configuredButNetworkFails_returnsNull() {
        // 已配置 → 真实 HTTP；离线必失败 → catch → null
        assertThat(service.createApproval("proc1", "请假", Map.of("days", 3))).isNull();
    }

    @Test
    void getApprovalStatus_configuredButNetworkFails_returnsNull() {
        assertThat(service.getApprovalStatus("inst123")).isNull();
    }

    @Test
    void cancelApproval_configuredButNetworkFails_doesNotThrow() {
        assertThatCode(() -> service.cancelApproval("inst123")).doesNotThrowAnyException();
    }

    // ── 回调（仅记录日志） ───────────────────────────────────
    @Test
    void handleApprovalCallback_agree_doesNotThrow() {
        assertThatCode(() -> service.handleApprovalCallback("inst123", "agree"))
                .doesNotThrowAnyException();
    }

    @Test
    void handleApprovalCallback_reject_doesNotThrow() {
        assertThatCode(() -> service.handleApprovalCallback("inst123", "reject"))
                .doesNotThrowAnyException();
    }
}
