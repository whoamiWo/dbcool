package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.audit.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Wave4 待办池 #6：工作流 webhook 触发 + 节点失败重试。
 */
class WorkflowWebhookAndRetryTest {

    private WorkflowRepository workflowRepository;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowEngine engine;
    private AuditService auditService;
    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        engine = mock(WorkflowEngine.class);
        auditService = mock(AuditService.class);
        controller = new WorkflowController(workflowRepository, instanceRepository, taskRepository,
                new ObjectMapper(), engine, auditService, new WorkflowGraphValidator());
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private WorkflowEntity webhookWorkflow(String token, boolean enabled) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName("hook");
        w.setCollectionName("posts");
        w.setNodesJson("[]");
        w.setEdgesJson("[]");
        w.setTriggerJson("{\"type\":\"webhook\",\"token\":\"" + token + "\"}");
        w.setEnabled(enabled);
        w.setTenantId("tenant_default");
        w.setCreatedBy(UUID.randomUUID());
        return w;
    }

    // ---------- #6a webhook 触发 ----------

    @Test
    void ensureWebhookToken_missingToken_generates32Hex() throws Exception {
        String out = controller.ensureWebhookToken("{\"type\":\"webhook\"}");
        assertTrue(out.contains("\"token\""));
        String token = new ObjectMapper().readTree(out).get("token").asText();
        assertEquals(32, token.length());
        assertTrue(token.matches("[0-9a-f]{32}"));
    }

    @Test
    void ensureWebhookToken_existingToken_kept() {
        String out = controller.ensureWebhookToken("{\"type\":\"webhook\",\"token\":\"abc123\"}");
        assertTrue(out.contains("abc123"));
    }

    @Test
    void ensureWebhookToken_nonWebhook_unchanged() {
        String in = "{\"type\":\"manual\"}";
        assertEquals(in, controller.ensureWebhookToken(in));
    }

    @Test
    void webhook_validToken_executesWorkflow() {
        WorkflowEntity w = webhookWorkflow("tok-valid-123", true);
        when(workflowRepository.findByWebhookToken("tok-valid-123")).thenReturn(List.of(w));
        when(engine.executeFrom(any(), any(), org.mockito.ArgumentMatchers.eq(0), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);

        var resp = controller.webhook("tok-valid-123", Map.of("x", 1));
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void webhook_unknownToken_returns404() {
        when(workflowRepository.findByWebhookToken("nope")).thenReturn(List.of());
        assertThrows(ResponseStatusException.class, () -> controller.webhook("nope", Map.of()));
    }

    @Test
    void webhook_disabledWorkflow_returns404() {
        WorkflowEntity w = webhookWorkflow("tok-disabled", false);
        when(workflowRepository.findByWebhookToken("tok-disabled")).thenReturn(List.of(w));
        assertThrows(ResponseStatusException.class, () -> controller.webhook("tok-disabled", Map.of()));
    }

    // ---------- #6b 节点失败重试 ----------

    @Test
    void retryCountOf_parsesConfigAndCapsAt3() {
        assertEquals(2, WorkflowEngine.retryCountOf(Map.of("config", Map.of("retry", 2))));
        assertEquals(3, WorkflowEngine.retryCountOf(Map.of("config", Map.of("retry", 9))));
        assertEquals(0, WorkflowEngine.retryCountOf(Map.of("config", Map.of())));
        assertEquals(1, WorkflowEngine.retryCountOf(Map.of("retry", "1")));
    }
}
