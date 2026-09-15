package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.notification.NotificationService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * WorkflowEngine 收尾测试(Week 32).
 * 覆盖 matchCondition 5 个 op(neq/contains/gt/lt/default) + 不存在的 op + null field
 * + 异常 op(method=FOO)+ logNotification fallback 路径
 * + executeGraphFrom 未知节点类型 / 无出边 / handle 不匹配 fallback.
 */
class WorkflowEngineMatchConditionTest {

    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowRepository workflowRepository;
    private MessageRepository messageRepository;
    private NotificationService notificationService;
    private RestTemplate restTemplate;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        messageRepository = mock(MessageRepository.class);
        notificationService = mock(NotificationService.class);
        restTemplate = mock(RestTemplate.class);

        engine = new WorkflowEngine(instanceRepository, taskRepository, workflowRepository,
                messageRepository, notificationService, new ObjectMapper(),
                new WorkflowNodeHandlerRegistry(java.util.List.of()));

        Field f = WorkflowEngine.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(engine, restTemplate);

        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private WorkflowInstanceEntity instance(String json) {
        WorkflowInstanceEntity i = new WorkflowInstanceEntity();
        i.setId(UUID.randomUUID());
        i.setWorkflowId(UUID.randomUUID());
        i.setTriggerDataJson(json);
        i.setTenantId("tenant_default");
        i.setStartedAt(Instant.now());
        return i;
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        if (config != null) n.put("config", config);
        return n;
    }

    /** 让 NOTIFICATION 节点的 recipient lookup 能成功。*/
    private void stubRecipientLookup(WorkflowInstanceEntity ins, UUID createdBy) {
        when(instanceRepository.findById(ins.getId())).thenReturn(Optional.of(ins));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(ins.getWorkflowId());
        wf.setCreatedBy(createdBy);
        when(workflowRepository.findById(ins.getWorkflowId())).thenReturn(Optional.of(wf));
    }

    // ============ matchCondition 5 个 op 覆盖 ============

    @Test
    void condition_neq_mismatch_returnsTrue() {
        WorkflowInstanceEntity ins = instance("{\"dept\":\"hr\"}");
        // dept=hr != eng → true → then
        assertConditionThen(ins,
                Map.of("field", "dept", "op", "neq", "value", "eng"),
                "B1");
    }

    @Test
    void condition_contains_substringMatch() {
        WorkflowInstanceEntity ins = instance("{\"name\":\"Alice\"}");
        // "Alice" 包含 "lic" → true → then
        assertConditionThen(ins,
                Map.of("field", "name", "op", "contains", "value", "lic"),
                "B1");
    }

    @Test
    void condition_gt_numeric_true() {
        WorkflowInstanceEntity ins = instance("{\"age\":25}");
        // 25 > 18 → true → then
        assertConditionThen(ins,
                Map.of("field", "age", "op", "gt", "value", 18),
                "B1");
    }

    @Test
    void condition_lt_numeric_true() {
        WorkflowInstanceEntity ins = instance("{\"age\":10}");
        // 10 < 20 → true → then
        assertConditionThen(ins,
                Map.of("field", "age", "op", "lt", "value", 20),
                "B1");
    }

    @Test
    void condition_unknownOp_returnsFalse() {
        WorkflowInstanceEntity ins = instance("{\"k\":\"v\"}");
        // op=weird → false → else
        assertConditionThen(ins,
                Map.of("field", "k", "op", "weird", "value", "v"),
                "B2");
    }

    @Test
    void condition_missingField_returnsFalse() {
        WorkflowInstanceEntity ins = instance("{}");
        // 没字段 → actual=null → false → else
        assertConditionThen(ins,
                Map.of("field", "missing", "op", "eq", "value", "x"),
                "B2");
    }

    @Test
    void condition_badJson_returnsFalse() {
        WorkflowInstanceEntity ins = instance("not json{");
        // data=null → actual=null → false → else
        assertConditionThen(ins,
                Map.of("field", "k", "op", "eq", "value", "v"),
                "B2");
    }

    @Test
    void condition_defaultOpIsEq() {
        WorkflowInstanceEntity ins = instance("{\"k\":\"v\"}");
        // 没 op → default "eq" → k=v 匹配 → then
        Map<String, Object> when = new LinkedHashMap<>();
        when.put("field", "k");
        when.put("value", "v");  // 无 op
        assertConditionThen(ins, when, "B1");
    }

    /** 通用助手:验证 CONDITION 节点命中 then/else 触发哪个 NOTIFICATION。*/
    private void assertConditionThen(WorkflowInstanceEntity ins,
                                     Map<String, Object> whenMap, String expectedBody) {
        stubRecipientLookup(ins, UUID.randomUUID());
        Map<String, Object> condCfg = Map.of(
                "when", whenMap,
                "then", "n2",
                "else", "n3");
        List<Map<String, Object>> nodes = List.of(
                node("n1", "CONDITION", condCfg),
                node("n2", "NOTIFICATION", Map.of("title", "T1", "message", "B1")),
                node("n3", "NOTIFICATION", Map.of("title", "T2", "message", "B2")));

        engine.executeFrom(ins, nodes, 0, null);

        ArgumentCaptor<MessageEntity> cap = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(m -> m.getBody().contains(expectedBody)),
                "应触发 body 含 " + expectedBody + " 的 NOTIFICATION,实际: "
                        + cap.getAllValues().stream().map(MessageEntity::getBody).toList());
    }

    // ============ executeGraphFrom 异常路径 ============

    @Test
    void executeGraphFrom_unknownNodeType_skipsAndContinues() {
        WorkflowInstanceEntity ins = instance("{}");
        stubRecipientLookup(ins, UUID.randomUUID());

        List<Map<String, Object>> nodes = List.of(
                node("n1", "GHOST_TYPE", null),
                node("n2", "NOTIFICATION", Map.of("title", "T", "message", "B")));
        List<Map<String, Object>> edges = List.of(
                Map.of("source", "n1", "target", "n2"));

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "n1", null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, times(1)).save(any(MessageEntity.class));  // n2 触发
    }

    @Test
    void executeGraphFrom_noOutgoingEdges_completes() {
        WorkflowInstanceEntity ins = instance("{}");
        stubRecipientLookup(ins, UUID.randomUUID());

        // n1 NOTIFICATION 无出边
        List<Map<String, Object>> nodes = List.of(
                node("n1", "NOTIFICATION", Map.of("title", "T", "message", "B")));
        List<Map<String, Object>> edges = List.of();

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "n1", null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        assertEquals(WorkflowInstanceEntity.Status.COMPLETED, ins.getStatus());
        verify(messageRepository, times(1)).save(any(MessageEntity.class));
    }

    @Test
    void executeGraphFrom_handleMismatch_fallsBackToFirstEdge() {
        // CONDITION true 但所有出边 handle 都是 false → fallback 第一个
        WorkflowInstanceEntity ins = instance("{\"x\":\"yes\"}");
        stubRecipientLookup(ins, UUID.randomUUID());

        Map<String, Object> cond = Map.of(
                "when", Map.of("field", "x", "op", "eq", "value", "yes"));
        List<Map<String, Object>> nodes = List.of(
                node("n1", "CONDITION", cond),
                node("n2", "NOTIFICATION", Map.of("title", "T", "message", "B")));
        List<Map<String, Object>> edges = List.of(
                Map.of("source", "n1", "target", "n2", "sourceHandle", "false"));

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(ins, nodes, edges, "n1", null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, times(1)).save(any(MessageEntity.class));
    }

    // ============ executeHttp 异常路径 ============

    @Test
    void executeFrom_httpMethodOmitted_defaultsToPost() {
        // 没指定 method → cfg.getOrDefault("method", "POST") → "POST"
        WorkflowInstanceEntity ins = instance("{}");
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(restTemplate.exchange(eq("https://example.com/api"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        Map<String, Object> cfg = Map.of("url", "https://example.com/api");
        // cfg 没 method 字段
        List<Map<String, Object>> nodes = List.of(node("n1", "HTTP", cfg));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);
        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        // 默认走 POST
        verify(restTemplate).exchange(eq("https://example.com/api"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    void executeFrom_httpCustomHeaders_applied() {
        WorkflowInstanceEntity ins = instance("{}");
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(restTemplate.exchange(anyString(), any(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        Map<String, Object> cfg = Map.of(
                "method", "POST",
                "url", "https://example.com/api",
                "headers", Map.of("X-Custom", "v1", "X-Auth", "tok"));
        List<Map<String, Object>> nodes = List.of(node("n1", "HTTP", cfg));

        engine.executeFrom(ins, nodes, 0, null);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<HttpEntity> cap = ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).exchange(anyString(), any(), cap.capture(), eq(String.class));
        HttpEntity entity = cap.getValue();
        assertEquals("v1", entity.getHeaders().getFirst("X-Custom"));
        assertEquals("tok", entity.getHeaders().getFirst("X-Auth"));
    }

    // ============ logNotification fallback 路径 ============

    @Test
    void executeFrom_logNotification_instanceNotFound_skipsMessage() {
        // instanceRepository.findById 返回 empty → recipient=null → 跳过
        WorkflowInstanceEntity ins = instance("{}");
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(instanceRepository.findById(ins.getId())).thenReturn(Optional.empty());

        List<Map<String, Object>> nodes = List.of(
                node("n1", "NOTIFICATION", Map.of("title", "T", "message", "B")));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, never()).save(any(MessageEntity.class));
        verify(notificationService, never()).fire(anyString(), anyString(), anyString(), any());
    }

    @Test
    void executeFrom_logNotification_invalidRecipientString_fallsBackToCreatedBy() {
        // recipient="not-a-uuid" → catch → fallback 到 createdBy
        WorkflowInstanceEntity ins = instance("{}");
        UUID createdBy = UUID.randomUUID();
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRecipientLookup(ins, createdBy);

        Map<String, Object> cfg = Map.of(
                "title", "T", "message", "B", "recipient", "not-a-uuid");
        List<Map<String, Object>> nodes = List.of(node("n1", "NOTIFICATION", cfg));

        engine.executeFrom(ins, nodes, 0, null);

        // fallback 到 createdBy(因为 recipient 字段解析失败)
        verify(notificationService).fire(eq("tenant_default"), anyString(),
                eq(createdBy.toString()), any());
    }

    @Test
    void executeFrom_logNotification_notificationServiceThrows_swallows() {
        // notificationService.fire 抛异常 → 整体继续
        WorkflowInstanceEntity ins = instance("{}");
        UUID createdBy = UUID.randomUUID();
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubRecipientLookup(ins, createdBy);
        org.mockito.Mockito.doThrow(new RuntimeException("kafka down"))
                .when(notificationService).fire(anyString(), anyString(), anyString(), any());

        Map<String, Object> cfg = Map.of("title", "T", "message", "B");
        List<Map<String, Object>> nodes = List.of(node("n1", "NOTIFICATION", cfg));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        // messageRepository 仍被调用(InApp 通知),fire 异常被吞
        verify(messageRepository, times(1)).save(any(MessageEntity.class));
    }
}
