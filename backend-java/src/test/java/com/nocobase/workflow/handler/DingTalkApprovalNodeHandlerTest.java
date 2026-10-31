package com.nocobase.workflow.handler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nocobase.integration.dingtalk.DingTalkApprovalService;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;

class DingTalkApprovalNodeHandlerTest {

    private final DingTalkApprovalService approvalService = mock(DingTalkApprovalService.class);
    private final com.nocobase.workflow.WorkflowInstanceRepository instanceRepository =
            org.mockito.Mockito.mock(com.nocobase.workflow.WorkflowInstanceRepository.class);
    private final DingTalkApprovalNodeHandler handler =
            new DingTalkApprovalNodeHandler(approvalService, instanceRepository);

    @Test
    void missingProcessCode_null_returnsContinue() {
        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        Map<String, Object> node = new HashMap<>();
        node.put("process_code", null);
        node.put("title", "Test");
        when(ctx.node()).thenReturn(node);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        verify(approvalService, never()).createApproval(anyString(), anyString(), any());
    }

    @Test
    void missingProcessCode_blank_returnsContinue() {
        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        Map<String, Object> node = new HashMap<>();
        node.put("process_code", "   ");
        node.put("title", "Test");
        when(ctx.node()).thenReturn(node);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        verify(approvalService, never()).createApproval(anyString(), anyString(), any());
    }

    @Test
    void createApprovalSuccess_logsInfoAndReturnsContinue() {
        String processCode = "PROC_123";
        String title = "请假申请";
        String instanceId = "INST_456";

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        Map<String, Object> node = new HashMap<>();
        node.put("process_code", processCode);
        node.put("title", title);
        when(ctx.node()).thenReturn(node);
        when(approvalService.createApproval(eq(processCode), eq(title), any()))
                .thenReturn(instanceId);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        verify(approvalService).createApproval(eq(processCode), eq(title), any());
    }

    @Test
    void createApprovalFailure_logsWarnAndReturnsContinue() {
        String processCode = "PROC_789";
        String title = "报销申请";

        NodeExecutionContext ctx = mock(NodeExecutionContext.class);
        Map<String, Object> node = new HashMap<>();
        node.put("process_code", processCode);
        node.put("title", title);
        when(ctx.node()).thenReturn(node);
        when(approvalService.createApproval(eq(processCode), eq(title), any()))
                .thenReturn(null);

        NodeOutcome outcome = handler.execute(ctx);

        assertEquals(NodeOutcome.CONTINUE, outcome);
        verify(approvalService).createApproval(eq(processCode), eq(title), any());
    }

    @Test
    void type_returnsDingTalkApproval() {
        assertEquals("DINGTALK_APPROVAL", handler.type());
    }
}
