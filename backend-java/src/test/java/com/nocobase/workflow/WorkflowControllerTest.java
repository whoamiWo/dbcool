package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.audit.AuditService;
import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import java.time.Instant;
import java.util.ArrayList;
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
 * WorkflowController 单测(Week 29).
 * 覆盖 9 endpoints: list/get/create/trigger/listInstances/getInstance/approve/reject/myTasks + approve 推进路径.
 */
class WorkflowControllerTest {

    private WorkflowRepository workflowRepository;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowEngine engine;
    private AuditService auditService;
    private WorkflowController controller;
    private AuthenticatedUser testUser;

    @BeforeEach
    void setUp() {
        workflowRepository = mock(WorkflowRepository.class);
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        engine = mock(WorkflowEngine.class);
        auditService = mock(AuditService.class);
        // Week 42 D4b.2: 真实校验器(mock 时为 no-op)
        com.nocobase.workflow.WorkflowGraphValidator graphValidator =
                new com.nocobase.workflow.WorkflowGraphValidator();
        controller = new WorkflowController(workflowRepository, instanceRepository,
                taskRepository, new ObjectMapper(), engine, auditService, graphValidator);

        testUser = new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");
        SecurityContextHolder.setContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(testUser, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))));

        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

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

    private WorkflowInstanceEntity makeInstance(UUID wfId) {
        WorkflowInstanceEntity i = new WorkflowInstanceEntity();
        i.setId(UUID.randomUUID());
        i.setWorkflowId(wfId);
        i.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        i.setTriggerDataJson("{}");
        i.setTenantId("tenant_default");
        i.setStartedAt(Instant.now());
        return i;
    }

    private WorkflowTaskEntity makeTask(UUID instanceId, WorkflowTaskEntity.Status status) {
        WorkflowTaskEntity t = new WorkflowTaskEntity();
        t.setId(UUID.randomUUID());
        t.setInstanceId(instanceId);
        t.setNodeId("n1");
        t.setNodeType("APPROVAL");
        t.setAssignee(testUser.userId());
        t.setStatus(status);
        t.setCreatedAt(Instant.now());
        return t;
    }

    // ============ list ============

    @Test
    void list_noCollection_returnsAll() {
        when(workflowRepository.findByTenantIdOrderByCreatedAtDesc("tenant_default"))
                .thenReturn(List.of(makeWorkflow(), makeWorkflow()));

        Map<String, Object> resp = controller.list(null);

        assertEquals(0, resp.get("code"));
        assertEquals(2, ((List<?>) resp.get("data")).size());
    }

    @Test
    void list_withCollection_returnsFiltered() {
        when(workflowRepository.findByCollectionNameAndTenantIdOrderByCreatedAtDesc(
                "posts", "tenant_default")).thenReturn(List.of(makeWorkflow()));

        Map<String, Object> resp = controller.list("posts");

        assertEquals(0, resp.get("code"));
        assertEquals(1, ((List<?>) resp.get("data")).size());
    }

    // ============ get ============

    @Test
    void get_returnsWorkflowWithParsedNodes() {
        WorkflowEntity w = makeWorkflow();
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"APPROVAL\"}]");
        w.setEdgesJson("[]");
        w.setTriggerJson("{\"type\":\"manual\"}");
        when(workflowRepository.findByIdAndTenantId(w.getId(), "tenant_default"))
                .thenReturn(Optional.of(w));

        Map<String, Object> resp = controller.get(w.getId());

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dto = (Map<String, Object>) resp.get("data");
        assertEquals(1, ((List<?>) dto.get("nodes")).size());
    }

    @Test
    void get_badJsonNodes_returnsEmpty() {
        WorkflowEntity w = makeWorkflow();
        w.setNodesJson("not json{");
        when(workflowRepository.findByIdAndTenantId(w.getId(), "tenant_default"))
                .thenReturn(Optional.of(w));

        Map<String, Object> resp = controller.get(w.getId());

        @SuppressWarnings("unchecked")
        Map<String, Object> dto = (Map<String, Object>) resp.get("data");
        assertEquals(0, ((List<?>) dto.get("nodes")).size());  // catch 里返回空 list
    }

    @Test
    void get_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(workflowRepository.findByIdAndTenantId(id, "tenant_default"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.get(id));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ============ create ============

    @Test
    void create_returns201() {
        WorkflowController.CreateWorkflowRequest req = new WorkflowController.CreateWorkflowRequest(
                "approval", "Approval Flow", "desc", "posts",
                "{\"type\":\"manual\"}", "[{\"id\":\"n1\"}]", "[]", true);

        ResponseEntity<Map<String, Object>> resp = controller.create(req, testUser);

        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        verify(workflowRepository, times(1)).save(any(WorkflowEntity.class));
    }

    @Test
    void create_withCycle_returns400() {
        // Week 42 D4b.2: 含环的工作流 → 静态校验拒绝
        WorkflowController.CreateWorkflowRequest req = new WorkflowController.CreateWorkflowRequest(
                "loop", "Loop Flow", "desc", "posts",
                "{\"type\":\"manual\"}",
                "[{\"id\":\"n1\"},{\"id\":\"n2\"}]",  // 2 nodes
                "[{\"source\":\"n1\",\"target\":\"n2\"},{\"source\":\"n2\",\"target\":\"n1\"}]",  // cycle!
                true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.create(req, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("工作流图校验失败"));
    }

    @Test
    void create_withInvalidJson_returns400() {
        // Week 42 D4b.2: 节点 JSON 解析失败
        WorkflowController.CreateWorkflowRequest req = new WorkflowController.CreateWorkflowRequest(
                "bad", "Bad", "d", "x",
                "{\"type\":\"manual\"}",
                "not-valid-json{",  // 非法 JSON
                "[]", true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.create(req, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("JSON 解析失败"));
    }

    // ============ trigger ============

    @Test
    void trigger_disabledWorkflow_returns400() {
        WorkflowEntity w = makeWorkflow();
        w.setEnabled(false);
        when(workflowRepository.findByIdAndTenantId(w.getId(), "tenant_default"))
                .thenReturn(Optional.of(w));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.trigger(w.getId(), Map.of(), testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void trigger_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(workflowRepository.findByIdAndTenantId(id, "tenant_default"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.trigger(id, null, testUser));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void trigger_completedSync() {
        WorkflowEntity w = makeWorkflow();
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"NOTIFICATION\",\"config\":{}}]");
        when(workflowRepository.findByIdAndTenantId(w.getId(), "tenant_default"))
                .thenReturn(Optional.of(w));
        when(engine.executeFrom(any(), any(), eq(0), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);

        ResponseEntity<Map<String, Object>> resp = controller.trigger(w.getId(), Map.of("k", "v"), testUser);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(auditService).log(eq("tenant_default"), eq(testUser.userId()),
                eq("alice"), eq("TRIGGER"), eq("workflow"), anyString(), any());
    }

    @Test
    void trigger_needsApproval_returns202() {
        WorkflowEntity w = makeWorkflow();
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"APPROVAL\"}]");
        w.setEdgesJson("[{\"source\":\"n1\",\"target\":\"n2\"}]");
        when(workflowRepository.findByIdAndTenantId(w.getId(), "tenant_default"))
                .thenReturn(Optional.of(w));
        when(engine.executeGraphFrom(any(), any(), any(), anyString(), any()))
                .thenReturn(WorkflowEngine.NodeResult.NEEDS_APPROVAL);

        ResponseEntity<Map<String, Object>> resp = controller.trigger(w.getId(), null, testUser);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertTrue(resp.getBody().get("message").toString().contains("waiting"));
    }

    @Test
    void trigger_failed_returns500() {
        WorkflowEntity w = makeWorkflow();
        when(workflowRepository.findByIdAndTenantId(w.getId(), "tenant_default"))
                .thenReturn(Optional.of(w));
        when(engine.executeFrom(any(), any(), anyInt(), any()))
                .thenReturn(WorkflowEngine.NodeResult.FAILED);

        ResponseEntity<Map<String, Object>> resp = controller.trigger(w.getId(), null, testUser);

        assertEquals(500, resp.getBody().get("code"));
    }

    @Test
    void trigger_badNodesJson_fallsBackToEmptyNodes() {
        WorkflowEntity w = makeWorkflow();
        w.setNodesJson("not json");
        when(workflowRepository.findByIdAndTenantId(w.getId(), "tenant_default"))
                .thenReturn(Optional.of(w));
        when(engine.executeFrom(any(), eq(List.of()), eq(0), any()))
                .thenReturn(WorkflowEngine.NodeResult.CONTINUE);

        ResponseEntity<Map<String, Object>> resp = controller.trigger(w.getId(), null, testUser);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    // ============ listInstances ============

    @Test
    void listInstances_noWorkflowId_returnsAll() {
        when(instanceRepository.findByTenantIdOrderByStartedAtDesc("tenant_default"))
                .thenReturn(List.of(makeInstance(UUID.randomUUID())));

        Map<String, Object> resp = controller.listInstances(null);

        assertEquals(0, resp.get("code"));
        assertEquals(1, ((List<?>) resp.get("data")).size());
    }

    @Test
    void listInstances_byWorkflowId_returnsFiltered() {
        UUID wfId = UUID.randomUUID();
        when(instanceRepository.findByWorkflowIdAndTenantIdOrderByStartedAtDesc(wfId, "tenant_default"))
                .thenReturn(List.of(makeInstance(wfId)));

        Map<String, Object> resp = controller.listInstances(wfId);

        assertEquals(1, ((List<?>) resp.get("data")).size());
    }

    // ============ getInstance ============

    @Test
    void getInstance_returnsInstanceWithTasks() {
        WorkflowInstanceEntity ins = makeInstance(UUID.randomUUID());
        when(instanceRepository.findByIdAndTenantId(ins.getId(), "tenant_default"))
                .thenReturn(Optional.of(ins));
        when(taskRepository.findByInstanceId(ins.getId()))
                .thenReturn(List.of(makeTask(ins.getId(), WorkflowTaskEntity.Status.PENDING)));

        Map<String, Object> resp = controller.getInstance(ins.getId());

        assertEquals(0, resp.get("code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> dto = (Map<String, Object>) resp.get("data");
        assertEquals(1, ((List<?>) dto.get("tasks")).size());
    }

    @Test
    void getInstance_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(instanceRepository.findByIdAndTenantId(id, "tenant_default"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getInstance(id));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ============ myTasks ============

    @Test
    void myTasks_returnsPending() {
        when(taskRepository.findByTenantIdAndAssigneeAndStatus(
                testUser.tenantId(), testUser.userId(), WorkflowTaskEntity.Status.PENDING))
                .thenReturn(List.of(makeTask(UUID.randomUUID(), WorkflowTaskEntity.Status.PENDING)));

        Map<String, Object> resp = controller.myTasks(testUser);

        assertEquals(0, resp.get("code"));
        assertEquals(1, ((List<?>) resp.get("data")).size());
    }

    /**
     * 多租户隔离:myTasks 必须带当前会话租户查询,
     * 否则用户经 UserTenantEntity 切换租户后会看到其他租户的待办(跨租户越权)。
     */
    @Test
    void myTasks_scopesQueryToCurrentTenant() {
        AuthenticatedUser otherTenantUser =
                new AuthenticatedUser(testUser.userId(), testUser.username(), "tenant_other");

        when(taskRepository.findByTenantIdAndAssigneeAndStatus(
                "tenant_other", testUser.userId(), WorkflowTaskEntity.Status.PENDING))
                .thenReturn(List.of());
        // 不带租户的旧查询若被调用会返回数据,用于证明它已不被使用
        when(taskRepository.findByAssigneeAndStatus(testUser.userId(), WorkflowTaskEntity.Status.PENDING))
                .thenReturn(List.of(makeTask(UUID.randomUUID(), WorkflowTaskEntity.Status.PENDING)));

        Map<String, Object> resp = controller.myTasks(otherTenantUser);

        assertEquals(0, ((List<?>) resp.get("data")).size());
        verify(taskRepository).findByTenantIdAndAssigneeAndStatus(
                "tenant_other", testUser.userId(), WorkflowTaskEntity.Status.PENDING);
        verify(taskRepository, never()).findByAssigneeAndStatus(any(), any());
    }


    // ============ approve ============

    @Test
    void approve_taskNotFound_returns404() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.approve(taskId, Map.of("comment", "ok"), testUser));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void approve_taskAlreadyHandled_returns400() {
        WorkflowTaskEntity t = makeTask(UUID.randomUUID(), WorkflowTaskEntity.Status.APPROVED);
        when(taskRepository.findById(t.getId())).thenReturn(Optional.of(t));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.approve(t.getId(), null, testUser));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void approve_withNextApprovalNode_returnsPending() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = makeWorkflow();
        w.setId(wfId);
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"APPROVAL\"},{\"id\":\"n2\",\"type\":\"APPROVAL\"}]");
        WorkflowInstanceEntity ins = makeInstance(wfId);
        ins.setCurrentNodeIndex(0);

        WorkflowTaskEntity task = makeTask(ins.getId(), WorkflowTaskEntity.Status.PENDING);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(instanceRepository.findById(ins.getId())).thenReturn(Optional.of(ins));
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        Map<String, Object> resp = controller.approve(task.getId(),
                Map.of("comment", "LGTM"), testUser);

        assertEquals(0, resp.get("code"));
        assertTrue(resp.get("message").toString().contains("next node waiting"));
        verify(taskRepository, times(2)).save(any(WorkflowTaskEntity.class));
    }

    @Test
    void approve_withNotificationOnly_completes() {
        UUID wfId = UUID.randomUUID();
        WorkflowEntity w = makeWorkflow();
        w.setId(wfId);
        w.setNodesJson("[{\"id\":\"n1\",\"type\":\"APPROVAL\"},{\"id\":\"n2\",\"type\":\"NOTIFICATION\"}]");
        WorkflowInstanceEntity ins = makeInstance(wfId);
        ins.setCurrentNodeIndex(0);

        WorkflowTaskEntity task = makeTask(ins.getId(), WorkflowTaskEntity.Status.PENDING);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(instanceRepository.findById(ins.getId())).thenReturn(Optional.of(ins));
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        Map<String, Object> resp = controller.approve(task.getId(), null, testUser);

        assertTrue(resp.get("message").toString().contains("completed"));
        assertEquals(WorkflowInstanceEntity.Status.COMPLETED, ins.getStatus());
    }

    // ============ reject ============

    @Test
    void reject_marksFailedAndAudit() {
        UUID wfId = UUID.randomUUID();
        WorkflowInstanceEntity ins = makeInstance(wfId);
        WorkflowEntity w = makeWorkflow();
        w.setId(wfId);
        WorkflowTaskEntity task = makeTask(ins.getId(), WorkflowTaskEntity.Status.PENDING);

        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(instanceRepository.findById(ins.getId())).thenReturn(Optional.of(ins));
        when(workflowRepository.findById(wfId)).thenReturn(Optional.of(w));

        Map<String, Object> resp = controller.reject(task.getId(),
                Map.of("comment", "no good"));

        assertEquals(0, resp.get("code"));
        assertEquals(WorkflowInstanceEntity.Status.FAILED, ins.getStatus());
        assertTrue(ins.getErrorMessage().contains("拒绝"));
        verify(auditService).log(eq("tenant_default"), eq(task.getAssignee()),
                eq(null), eq("REJECT"), eq("workflow_task"), anyString(), any());
    }

    @Test
    void reject_taskNotFound_returns404() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reject(taskId, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void reject_taskAlreadyHandled_returns400() {
        WorkflowTaskEntity t = makeTask(UUID.randomUUID(), WorkflowTaskEntity.Status.REJECTED);
        when(taskRepository.findById(t.getId())).thenReturn(Optional.of(t));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.reject(t.getId(), null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }
}
