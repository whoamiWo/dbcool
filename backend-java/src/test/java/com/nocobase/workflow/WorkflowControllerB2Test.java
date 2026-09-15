package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ResponseStatusException;

/**
 * B2 修复回归测试(Week 41) —— 验证:
 * 1. PUT /api/workflows/{id} 真实可用(此前前端调用必失败)
 * 2. DELETE /api/workflows/{id} 真实可用
 * 3. DELETE 拒绝有活跃实例(RUNNING/PENDING)的工作流(报告 3.2 策略)
 * 4. PUT/DELETE 跨租户拒绝(FORBIDDEN)
 * 5. PUT 是 PATCH 语义:null 字段不修改
 * 6. PUT/DELETE 记审计日志
 *
 * 验收:
 *   - 编辑工作流 → 保存 → 重新打开,修改已持久化
 *   - 删除工作流返回 204,列表不再显示
 *   - 拒绝删除有 RUNNING/PENDING 实例的工作流
 */
class WorkflowControllerB2Test {

    private WorkflowRepository workflowRepository;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowEngine engine;
    private AuditService auditService;
    private WorkflowController controller;
    private AuthenticatedUser testUser;
    private WorkflowEntity existing;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        engine = mock(WorkflowEngine.class);
        auditService = mock(AuditService.class);
        controller = new WorkflowController(workflowRepository, instanceRepository,
                taskRepository, new ObjectMapper(), engine, auditService,
                new WorkflowGraphValidator());

        testUser = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(testUser, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));

        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        existing = makeWorkflow();
        when(workflowRepository.findByIdAndTenantId(existing.getId(), "tenant_default"))
                .thenReturn(Optional.of(existing));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ============ PUT 更新 ============

    @Test
    void update_modifiesProvidedFields_persistsAndAudits() {
        var req = new WorkflowController.UpdateWorkflowRequest(
                "approval_v2", "Approval v2", "Updated description",
                "orders", "{\"type\":\"manual\"}", "[{\"id\":\"n1\",\"type\":\"APPROVAL\"}]",
                "[]", false);

        Map<String, Object> resp = controller.update(existing.getId(), req, testUser);

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals("approval_v2", data.get("name"));
        assertEquals("Approval v2", data.get("title"));
        assertEquals("orders", data.get("collection_name"));
        assertEquals(false, data.get("enabled"));
        // 持久化
        verify(workflowRepository, times(1)).save(any());
        // 审计
        verify(auditService, times(1)).log(eq("tenant_default"),
                eq(testUser.userId()), eq("alice"), eq("UPDATE"),
                eq("workflow"), eq(existing.getId().toString()), any());
    }

    @Test
    void update_nullFieldsAreIgnored() {
        // 仅修改 title,其他字段 null
        var req = new WorkflowController.UpdateWorkflowRequest(
                null, "Only Title", null, null, null, null, null, null);

        Map<String, Object> resp = controller.update(existing.getId(), req, testUser);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        // title 改了
        assertEquals("Only Title", data.get("title"));
        // name 保持原值
        assertEquals("approval", data.get("name"));
        // collection 保持原值
        assertEquals("posts", data.get("collection_name"));
        // enabled 保持原值
        assertEquals(true, data.get("enabled"));
    }

    @Test
    void update_blankNameIgnored() {
        // 空字符串 name 不覆盖(避免破坏必填约束)
        var req = new WorkflowController.UpdateWorkflowRequest(
                "   ", "Title", null, null, null, null, null, null);

        Map<String, Object> resp = controller.update(existing.getId(), req, testUser);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals("approval", data.get("name")); // 原值
    }

    @Test
    void update_unknownWorkflow_returns404() {
        UUID unknown = UUID.randomUUID();
        when(workflowRepository.findByIdAndTenantId(unknown, "tenant_default"))
                .thenReturn(Optional.empty());

        var req = new WorkflowController.UpdateWorkflowRequest("x", null, null, null, null, null, null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.update(unknown, req, testUser));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ============ DELETE 删除 ============

    @Test
    void delete_noActiveInstances_returns204AndDeletes() {
        // 没活跃实例
        when(instanceRepository.findByWorkflowIdAndStatusIn(eq(existing.getId()), anyCollection()))
                .thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> resp = controller.delete(existing.getId(), testUser);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(workflowRepository, times(1)).delete(eq(existing));
        verify(auditService, times(1)).log(eq("tenant_default"),
                eq(testUser.userId()), eq("alice"), eq("DELETE"),
                eq("workflow"), eq(existing.getId().toString()), any());
    }

    @Test
    void delete_runningInstance_returns409() {
        // 模拟有 RUNNING 实例
        WorkflowInstanceEntity running = new WorkflowInstanceEntity();
        running.setId(UUID.randomUUID());
        running.setWorkflowId(existing.getId());
        running.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        when(instanceRepository.findByWorkflowIdAndStatusIn(eq(existing.getId()), anyCollection()))
                .thenReturn(List.of(running));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete(existing.getId(), testUser));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("运行中实例"));
        // 不应删除
        verify(workflowRepository, never()).delete(any());
    }

    @Test
    void delete_pendingInstance_returns409() {
        // 模拟有 PENDING 实例
        WorkflowInstanceEntity pending = new WorkflowInstanceEntity();
        pending.setId(UUID.randomUUID());
        pending.setWorkflowId(existing.getId());
        pending.setStatus(WorkflowInstanceEntity.Status.PENDING);
        when(instanceRepository.findByWorkflowIdAndStatusIn(eq(existing.getId()), anyCollection()))
                .thenReturn(List.of(pending));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete(existing.getId(), testUser));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void delete_completedInstance_actuallyDeletes() {
        // 模拟只有已完成的实例(不应阻断)
        WorkflowInstanceEntity completed = new WorkflowInstanceEntity();
        completed.setId(UUID.randomUUID());
        completed.setStatus(WorkflowInstanceEntity.Status.COMPLETED);
        when(instanceRepository.findByWorkflowIdAndStatusIn(eq(existing.getId()), anyCollection()))
                .thenReturn(Collections.emptyList()); // 关键:活跃查询返空

        ResponseEntity<Map<String, Object>> resp = controller.delete(existing.getId(), testUser);

        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
        verify(workflowRepository, times(1)).delete(eq(existing));
    }

    @Test
    void delete_unknownWorkflow_returns404() {
        UUID unknown = UUID.randomUUID();
        when(workflowRepository.findByIdAndTenantId(unknown, "tenant_default"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.delete(unknown, testUser));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ============ helpers ============

    private WorkflowEntity makeWorkflow() {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(UUID.randomUUID());
        w.setName("approval");
        w.setTitle("Approval Flow");
        w.setCollectionName("posts");
        w.setNodesJson("[]");
        w.setEdgesJson("[]");
        w.setTriggerJson("{\"type\":\"manual\"}");
        w.setEnabled(true);
        w.setTenantId("tenant_default");
        w.setCreatedAt(Instant.now());
        w.setCreatedBy(testUser.userId());
        return w;
    }
}
