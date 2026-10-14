package com.nocobase.integration.dingtalk;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PHASE105: DingTalkApprovalService 分支覆盖（未配置/空输入）。 */
public class DingTalkApprovalServiceTest {

    private DingTalkApprovalService service;
    private DingTalkAppService appService;

    @BeforeEach
    void setUp() {
        appService = mock(DingTalkAppService.class);
        when(appService.isConfigured()).thenReturn(true);
        service = new DingTalkApprovalService(appService);
    }

    @Test
    void createApproval_notConfigured_returnsNull() {
        when(appService.isConfigured()).thenReturn(false);
        String id = service.createApproval("proc1", "请假", Map.of());
        assertNull(id);
    }

    @Test
    void getApprovalStatus_notConfigured_returnsNull() {
        when(appService.isConfigured()).thenReturn(false);
        assertNull(service.getApprovalStatus("inst123"));
    }

    @Test
    void getApprovalStatus_nullInstanceId_returnsNull() {
        when(appService.isConfigured()).thenReturn(true);
        assertNull(service.getApprovalStatus(null));
    }

    @Test
    void cancelApproval_notConfigured_returnsEarly() {
        when(appService.isConfigured()).thenReturn(false);
        assertDoesNotThrow(() -> service.cancelApproval("inst123"));
    }

    @Test
    void handleApprovalCallback_logsInfo() {
        assertDoesNotThrow(() -> service.handleApprovalCallback("inst123", "agree"));
    }
}
