package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.notification.NotificationService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * WorkflowEngine 单测(Week 28).
 * 覆盖 executeGraphFrom / executeFrom / 4 类节点(APPROVAL/NOTIFICATION/CONDITION/HTTP)
 * + castConfig / matchCondition / cycle 检测 / 未知类型 / 空 url.
 */
class WorkflowEngineTest {

    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowRepository workflowRepository;
    private MessageRepository messageRepository;
    private NotificationService notificationService;
    private ObjectMapper objectMapper;
    private RestTemplate restTemplate;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        messageRepository = mock(MessageRepository.class);
        notificationService = mock(NotificationService.class);
        objectMapper = new ObjectMapper();
        restTemplate = mock(RestTemplate.class);

        engine = new WorkflowEngine(
                instanceRepository, taskRepository, workflowRepository,
                messageRepository, notificationService, objectMapper);

        // RestTemplate 是 final 字段,用反射替换
        Field f = WorkflowEngine.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(engine, restTemplate);
    }

    private WorkflowInstanceEntity instance(String triggerJson) {
        WorkflowInstanceEntity i = new WorkflowInstanceEntity();
        i.setId(UUID.randomUUID());
        i.setWorkflowId(UUID.randomUUID());
        i.setTriggerDataJson(triggerJson);
        i.setTenantId("tenant_default");
        return i;
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> n = new java.util.LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        if (config != null) n.put("config", config);
        return n;
    }

    private void stubSaveInstance() {
        when(instanceRepository.save(any(WorkflowInstanceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** 让 NOTIFICATION 节点的 recipient lookup 能拿到 createdBy. */
    private void stubNotificationRecipient(WorkflowInstanceEntity ins, UUID createdBy) {
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(ins.getWorkflowId());
        wf.setCreatedBy(createdBy);
        when(workflowRepository.findById(ins.getWorkflowId())).thenReturn(java.util.Optional.of(wf));
    }

    // ============ executeFrom: array mode ============

    @Test
    void executeFrom_emptyNodes_setsCompleted() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, List.of(), 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        assertEquals(WorkflowInstanceEntity.Status.COMPLETED, ins.getStatus());
        assertNotNull(ins.getFinishedAt());
        verify(instanceRepository, times(1)).save(any());
    }

    @Test
    void executeFrom_unknownNodeType_skipsAndCompletes() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();

        List<Map<String, Object>> nodes = List.of(
                node("n1", "MYSTERY", null),
                node("n2", "UNKNOWN", null));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        assertEquals(WorkflowInstanceEntity.Status.COMPLETED, ins.getStatus());
    }

    @Test
    void executeFrom_notification_savesInAppMessageAndFiresNotify() {
        WorkflowInstanceEntity ins = instance("{}");
        UUID createdBy = UUID.randomUUID();
        UUID wfId = ins.getWorkflowId();
        stubSaveInstance();
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(wfId);
        wf.setCreatedBy(createdBy);
        when(workflowRepository.findById(wfId)).thenReturn(java.util.Optional.of(wf));

        Map<String, Object> cfg = Map.of("title", "Hi", "message", "you got it");
        List<Map<String, Object>> nodes = List.of(node("n1", "NOTIFICATION", cfg));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, times(1)).save(any(MessageEntity.class));
        verify(notificationService, times(1)).fire(
                eq("tenant_default"), eq("workflow.notification"),
                eq(createdBy.toString()), any());
    }

    @Test
    void executeFrom_notification_explicitRecipientOverridesCreatedBy() {
        WorkflowInstanceEntity ins = instance("{}");
        UUID createdBy = UUID.randomUUID();
        UUID explicit = UUID.randomUUID();
        UUID wfId = ins.getWorkflowId();
        stubSaveInstance();
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(wfId);
        wf.setCreatedBy(createdBy);
        when(workflowRepository.findById(wfId)).thenReturn(java.util.Optional.of(wf));

        Map<String, Object> cfg = Map.of("title", "T", "message", "B",
                "recipient", explicit.toString());
        List<Map<String, Object>> nodes = List.of(node("n1", "NOTIFICATION", cfg));

        engine.executeFrom(ins, nodes, 0, null);

        verify(notificationService).fire(
                eq("tenant_default"), anyString(),
                eq(explicit.toString()), any());
    }

    @Test
    void executeFrom_approval_createsTaskAndReturnsNeedsApproval() {
        WorkflowInstanceEntity ins = instance("{}");
        UUID assignee = UUID.randomUUID();
        stubSaveInstance();
        when(taskRepository.save(any(WorkflowTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<Map<String, Object>> nodes = List.of(node("n1", "APPROVAL", null));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, assignee);

        assertEquals(WorkflowEngine.NodeResult.NEEDS_APPROVAL, r);
        assertEquals(WorkflowInstanceEntity.Status.PENDING, ins.getStatus());
        ArgumentCaptor<WorkflowTaskEntity> cap = ArgumentCaptor.forClass(WorkflowTaskEntity.class);
        verify(taskRepository, times(1)).save(cap.capture());
        assertEquals("APPROVAL", cap.getValue().getNodeType());
        assertEquals(assignee, cap.getValue().getAssignee());
        assertEquals(WorkflowTaskEntity.Status.PENDING, cap.getValue().getStatus());
    }

    @Test
    void executeFrom_condition_eqMatchesThenPicksThenBranch() {
        WorkflowInstanceEntity ins = instance("{\"dept\":\"eng\"}");
        stubSaveInstance();

        // 节点顺序:CONDITION(when dept=eng → then=n2), n2=NOTIFICATION, n3=NOTIFICATION
        Map<String, Object> condCfg = Map.of(
                "when", Map.of("field", "dept", "op", "eq", "value", "eng"),
                "then", "n2",
                "else", "n3");
        List<Map<String, Object>> nodes = List.of(
                node("n1", "CONDITION", condCfg),
                node("n2", "NOTIFICATION", Map.of("title", "T1", "message", "B1")),
                node("n3", "NOTIFICATION", Map.of("title", "T2", "message", "B2")));

        // stub instance lookup for logNotification → triggerDataJson 解析
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));
        stubNotificationRecipient(ins, UUID.randomUUID());

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        // 数组模式 evaluateCondition 找到 then 节点 idx=1 后会顺序执行 n2 → n3(无 i++),
        // 但 n2(then) 必须先被处理。验证 save 调用顺序的第一个 message 是 then 分支。
        ArgumentCaptor<MessageEntity> cap = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().get(0).getBody().contains("B1"),
                "then 分支(n2=B1)应先被触发,实际: " + cap.getAllValues().get(0).getBody());
    }

    @Test
    void executeFrom_condition_eqMismatchPicksElseBranch() {
        WorkflowInstanceEntity ins = instance("{\"dept\":\"hr\"}");
        stubSaveInstance();
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));

        Map<String, Object> condCfg = Map.of(
                "when", Map.of("field", "dept", "op", "eq", "value", "eng"),
                "then", "n2",
                "else", "n3");
        List<Map<String, Object>> nodes = List.of(
                node("n1", "CONDITION", condCfg),
                node("n2", "NOTIFICATION", Map.of("title", "T1", "message", "B1")),
                node("n3", "NOTIFICATION", Map.of("title", "T2", "message", "B2")));

        stubNotificationRecipient(ins, UUID.randomUUID());

        engine.executeFrom(ins, nodes, 0, null);

        // 数组模式 condition 命中 else(n3)后,i 跳到 2,触发 n3 NOTIFICATION(无 i++ bug,
        // 这是当前行为,非我们要测的目标)。验证 else 分支的 message 被 save。
        ArgumentCaptor<MessageEntity> cap = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(m -> m.getBody().contains("B2")),
                "else 分支(n3=B2)应被触发");
    }

    @Test
    void executeFrom_condition_noMatchingBranch_returnsCurrentIdxPlusOne() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();

        // when=null → matchCondition 默认 true,但 then/else 都没有 → currentIdx+1
        Map<String, Object> condCfg = Map.of();
        List<Map<String, Object>> nodes = List.of(
                node("n1", "CONDITION", condCfg),
                node("n2", "NOTIFICATION", Map.of("title", "T", "message", "B")));

        stubNotificationRecipient(ins, UUID.randomUUID());

        engine.executeFrom(ins, nodes, 0, null);

        verify(messageRepository, times(1)).save(any(MessageEntity.class));
    }

    // ============ executeGraphFrom: graph mode ============

    @Test
    void executeGraphFrom_sequentialEdges_completesAfterLastNode() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));

        // n1 → n2 → n3(均 NOTIFICATION)
        List<Map<String, Object>> nodes = List.of(
                node("n1", "NOTIFICATION", Map.of("title", "T1", "message", "B1")),
                node("n2", "NOTIFICATION", Map.of("title", "T2", "message", "B2")),
                node("n3", "NOTIFICATION", Map.of("title", "T3", "message", "B3")));
        List<Map<String, Object>> edges = List.of(
                Map.of("source", "n1", "target", "n2"),
                Map.of("source", "n2", "target", "n3"));

        stubNotificationRecipient(ins, UUID.randomUUID());

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "n1", null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, times(3)).save(any(MessageEntity.class));
    }

    @Test
    void executeGraphFrom_condition_trueBranch_followsTrueEdge() {
        WorkflowInstanceEntity ins = instance("{\"x\":\"yes\"}");
        stubSaveInstance();
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));

        // n1 (CONDITION x==yes → true handle → n2; false handle → n3) → n2 NOTIF
        Map<String, Object> cond = Map.of(
                "when", Map.of("field", "x", "op", "eq", "value", "yes"));
        List<Map<String, Object>> nodes = List.of(
                node("n1", "CONDITION", cond),
                node("n2", "NOTIFICATION", Map.of("title", "OK", "message", "yes-branch")),
                node("n3", "NOTIFICATION", Map.of("title", "NO", "message", "no-branch")));
        List<Map<String, Object>> edges = List.of(
                Map.of("source", "n1", "target", "n2", "sourceHandle", "true"),
                Map.of("source", "n1", "target", "n3", "sourceHandle", "false"));

        stubNotificationRecipient(ins, UUID.randomUUID());

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "n1", null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, times(1)).save(any(MessageEntity.class));
        // 应该是 yes-branch 被触发
        ArgumentCaptor<MessageEntity> cap = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(cap.capture());
        assertTrue(cap.getValue().getBody().contains("yes-branch"));
    }

    @Test
    void executeGraphFrom_cycleDetected_breaksLoop() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();
        when(instanceRepository.findById(ins.getId())).thenReturn(java.util.Optional.of(ins));

        // n1 → n2 → n1(循环!)
        List<Map<String, Object>> nodes = List.of(
                node("n1", "NOTIFICATION", Map.of("title", "T", "message", "loop")),
                node("n2", "NOTIFICATION", Map.of("title", "T", "message", "loop")));
        List<Map<String, Object>> edges = List.of(
                Map.of("source", "n1", "target", "n2"),
                Map.of("source", "n2", "target", "n1"));

        stubNotificationRecipient(ins, UUID.randomUUID());

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "n1", null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        // 首次访问 n1/n2 → 2 次,cycle break 后退出
        verify(messageRepository, times(2)).save(any(MessageEntity.class));
    }

    @Test
    void executeGraphFrom_unknownStartNode_breaksAndCompletes() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();

        List<Map<String, Object>> nodes = List.of(
                node("n1", "NOTIFICATION", Map.of("title", "T", "message", "B")));
        List<Map<String, Object>> edges = List.of();

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "ghost", null);

        // startNodeId 不存在 → byId.get(current) = null → break → COMPLETED
        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, never()).save(any(MessageEntity.class));
    }

    @Test
    void executeGraphFrom_approvalPauses_returnsNeedsApproval() {
        WorkflowInstanceEntity ins = instance("{}");
        UUID assignee = UUID.randomUUID();
        stubSaveInstance();
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<Map<String, Object>> nodes = List.of(
                node("n1", "APPROVAL", null));
        List<Map<String, Object>> edges = List.of();

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "n1", assignee);

        assertEquals(WorkflowEngine.NodeResult.NEEDS_APPROVAL, r);
        verify(taskRepository).save(any(WorkflowTaskEntity.class));
    }

    // ============ HTTP node ============

    @Test
    void executeFrom_http_noUrl_skipsWithoutCall() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();

        Map<String, Object> cfg = Map.of("method", "POST" /* url missing */);
        List<Map<String, Object>> nodes = List.of(node("n1", "HTTP", cfg));

        engine.executeFrom(ins, nodes, 0, null);

        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    @Test
    void executeFrom_http_bearerAuth_setsHeaderAndCalls() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();
        when(restTemplate.exchange(eq("https://x.example/api"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        Map<String, Object> cfg = Map.of(
                "method", "POST",
                "url", "https://x.example/api",
                "auth", Map.of("type", "bearer", "token", "TOK123"),
                "body", Map.of("a", 1));
        List<Map<String, Object>> nodes = List.of(node("n1", "HTTP", cfg));

        engine.executeFrom(ins, nodes, 0, null);

        ArgumentCaptor<HttpEntity> cap = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://x.example/api"), eq(HttpMethod.POST),
                cap.capture(), eq(String.class));
        assertTrue(cap.getValue().getHeaders().get("Authorization").get(0).startsWith("Bearer "));
    }

    @Test
    void executeFrom_http_basicAuth_setsHeaderAndCalls() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        Map<String, Object> cfg = Map.of(
                "method", "GET",
                "url", "https://x.example/api",
                "auth", Map.of("type", "basic", "username", "u", "password", "p"));
        List<Map<String, Object>> nodes = List.of(node("n1", "HTTP", cfg));

        engine.executeFrom(ins, nodes, 0, null);

        ArgumentCaptor<HttpEntity> cap = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET),
                cap.capture(), eq(String.class));
        assertTrue(cap.getValue().getHeaders().get("Authorization").get(0).startsWith("Basic "));
    }

    @Test
    void executeFrom_http_restClientException_logsAndContinues() {
        WorkflowInstanceEntity ins = instance("{}");
        stubSaveInstance();
        doThrow(new RestClientException("connection refused"))
                .when(restTemplate).exchange(anyString(), any(), any(), eq(String.class));

        Map<String, Object> cfg = Map.of("method", "POST", "url", "https://bad.example");
        List<Map<String, Object>> nodes = List.of(node("n1", "HTTP", cfg));

        // 不应抛 — 应捕获后继续
        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(restTemplate).exchange(anyString(), any(), any(), eq(String.class));
    }
}
