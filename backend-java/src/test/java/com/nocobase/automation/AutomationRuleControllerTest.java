package com.nocobase.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.automation.entity.AutomationExecutionEntity;
import com.nocobase.automation.entity.AutomationRuleEntity;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.auth.JwtService;
import com.nocobase.auth.RefreshTokenService;
import com.nocobase.audit.AuditService;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * AutomationRuleController 端点测试（PHASE108 冲 85%）。
 *
 * <p>覆盖 create/update/toggle/delete/list/get/execute/listExecutions 共 9 个端点。
 */
class AutomationRuleControllerTest {

    private AutomationRuleService service;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private AuditService auditService;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private AutomationRuleController controller;

    @BeforeEach
    void setUp() {
        service = mock(AutomationRuleService.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        auditService = mock(AuditService.class);
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);

        controller = new AutomationRuleController(service);
    }

    private AuthenticatedUser user(String tenantId, UUID userId) {
        return new AuthenticatedUser(userId, "alice", tenantId);
    }

    @Test
    void create_success_returnsCreated() {
        UUID ruleId = UUID.randomUUID();
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(ruleId);
        rule.setName("rule1");
        when(service.createRule(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), any()))
                .thenReturn(rule);

        ResponseEntity<Map<String, Object>> r = controller.create(
                Map.of("name", "rule1", "triggerType", "manual"),
                user("tenant1", UUID.randomUUID()));

        assertEquals(201, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(ruleId.toString(), data.get("id"));
    }

    @Test
    void update_success_returnsOk() {
        UUID ruleId = UUID.randomUUID();
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(ruleId);
        when(service.updateRule(eq(ruleId), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(rule);

        ResponseEntity<Map<String, Object>> r = controller.update(
                ruleId, Map.of("name", "updated"), user("tenant1", UUID.randomUUID()));

        assertEquals(200, r.getStatusCode().value());
        verify(service).updateRule(ruleId, "updated", null, null, null, "tenant1");
    }

    @Test
    void toggle_success_returnsEnabled() {
        UUID ruleId = UUID.randomUUID();
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setEnabled(true);
        when(service.toggleRule(eq(ruleId), eq(true), anyString())).thenReturn(rule);

        ResponseEntity<Map<String, Object>> r = controller.toggle(
                ruleId, Map.of("enabled", "true"), user("tenant1", UUID.randomUUID()));

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(true, data.get("enabled"));
    }

    @Test
    void delete_success_returnsOk() {
        UUID ruleId = UUID.randomUUID();
        // deleteRule 是 void，直接 verify 调用即可
        when(service.getRule(eq(ruleId), anyString())).thenReturn(new AutomationRuleEntity());

        ResponseEntity<Map<String, Object>> r = controller.delete(
                ruleId, user("tenant1", UUID.randomUUID()));

        assertEquals(200, r.getStatusCode().value());
        verify(service).deleteRule(ruleId, "tenant1");
    }

    @Test
    void list_returnsRules() {
        UUID ruleId = UUID.randomUUID();
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(ruleId);
        when(service.listRules("tenant1")).thenReturn(List.of(rule));

        ResponseEntity<Map<String, Object>> r = controller.list(user("tenant1", UUID.randomUUID()));

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(List.of(rule), data.get("rules"));
    }

    @Test
    void get_success_returnsRule() {
        UUID ruleId = UUID.randomUUID();
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId(ruleId);
        when(service.getRule(eq(ruleId), anyString())).thenReturn(rule);

        ResponseEntity<Map<String, Object>> r = controller.get(
                ruleId, user("tenant1", UUID.randomUUID()));

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(ruleId.toString(), data.get("id"));
    }

    @Test
    void execute_success_returnsExecution() {
        UUID ruleId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AutomationExecutionEntity exec = new AutomationExecutionEntity();
        exec.setId(UUID.randomUUID());
        exec.setStatus("SUCCESS");
        when(service.executeRule(eq(ruleId), any(), eq(userId), anyString()))
                .thenReturn(exec);

        ResponseEntity<Map<String, Object>> r = controller.execute(
                ruleId, Map.of("triggerData", Map.of("k", "v")), user("tenant1", userId));

        assertEquals(200, r.getStatusCode().value());
        verify(service).executeRule(ruleId, Map.of("k", "v"), userId, "tenant1");
    }

    @Test
    void listExecutions_returnsHistory() {
        UUID ruleId = UUID.randomUUID();
        AutomationExecutionEntity exec = new AutomationExecutionEntity();
        when(service.listExecutions(eq(ruleId), anyString())).thenReturn(List.of(exec));

        ResponseEntity<Map<String, Object>> r = controller.listExecutions(
                ruleId, user("tenant1", UUID.randomUUID()));

        assertEquals(200, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(List.of(exec), data.get("executions"));
    }

    @Test
    void create_nullBody_returnsNullRule() {
        when(service.createRule(null, null, null, null, null, null, null, null, null))
                .thenReturn(null);

        ResponseEntity<Map<String, Object>> r = controller.create(null, user("tenant1", UUID.randomUUID()));

        assertEquals(201, r.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getBody().get("data");
        assertEquals(null, data);
    }
}
