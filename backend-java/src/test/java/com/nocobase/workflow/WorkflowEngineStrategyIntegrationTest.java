package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.notification.NotificationService;
import com.nocobase.workflow.handler.ApprovalNodeHandler;
import com.nocobase.workflow.handler.ConditionNodeHandler;
import com.nocobase.workflow.handler.HttpNodeHandler;
import com.nocobase.workflow.handler.NotificationNodeHandler;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * 策略化分发集成测试(Week 41 复核新增)。
 *
 * <p>背景:既有 {@code WorkflowEngineTest} 注入的是<strong>空 registry</strong>
 * ({@code new WorkflowNodeHandlerRegistry(List.of())}),且 strategy 用例只用
 * {@code CUSTOM_*} 假类型。这导致两个真实缺陷完全逃逸于 581 个测试之外:
 * <ol>
 *   <li>4 个内置 handler 是死代码(registry 分支挂在 4 个 legacy 分支之后)</li>
 *   <li>handler 能力缺失(漏 NOT NULL 字段、丢站内信与多渠道、丢 HTTP 鉴权)</li>
 * </ol>
 *
 * <p>本类使用<strong>真实 handler 实例 + 真实 engine</strong>,锁住:
 * <ol>
 *   <li>registry 优先于 legacy —— 内置类型也走 handler</li>
 *   <li>ApprovalNodeHandler 补齐 nodeType / createdAt</li>
 *   <li>NotificationNodeHandler 写站内信 + 多渠道 fire</li>
 *   <li>HttpNodeHandler 应用鉴权 + method 大写化</li>
 *   <li>ConditionNodeHandler 支持 contains 操作符</li>
 * </ol>
 */
class WorkflowEngineStrategyIntegrationTest {

    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowRepository workflowRepository;
    private MessageRepository messageRepository;
    private NotificationService notificationService;
    private ObjectMapper objectMapper;
    private RestTemplate httpRestTemplate;

    /** 用真实 4 个 handler 组装的引擎(模拟生产环境 Spring 注入)。 */
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        messageRepository = mock(MessageRepository.class);
        notificationService = mock(NotificationService.class);
        objectMapper = new ObjectMapper();

        when(instanceRepository.save(any(WorkflowInstanceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // HttpNodeHandler 内部自己 new RestTemplate(),用反射换成 mock
        httpRestTemplate = mock(RestTemplate.class);

        HttpNodeHandler httpHandler = new HttpNodeHandler();
        Field f = HttpNodeHandler.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(httpHandler, httpRestTemplate);

        engine = new WorkflowEngine(
                instanceRepository,
                taskRepository,
                workflowRepository,
                messageRepository,
                notificationService,
                objectMapper,
                new WorkflowNodeHandlerRegistry(List.of(
                        new ApprovalNodeHandler(instanceRepository, taskRepository),
                        new NotificationNodeHandler(workflowRepository, messageRepository, notificationService),
                        httpHandler,
                        new ConditionNodeHandler())));
    }

    // ------------------------------------------------------------------
    // 1. 分发优先级:registry 必须优先于 legacy
    // ------------------------------------------------------------------

    @Test
    void dispatch_prefersRegistryHandlerOverLegacyPath() {
        // 注册一个 type=APPROVAL 的 mock handler 覆盖内置实现
        WorkflowNodeHandler mockApproval = mock(WorkflowNodeHandler.class);
        when(mockApproval.type()).thenReturn("APPROVAL");
        when(mockApproval.execute(any(NodeExecutionContext.class))).thenReturn(NodeOutcome.CONTINUE);

        WorkflowEngine local = new WorkflowEngine(
                instanceRepository, taskRepository, workflowRepository,
                messageRepository, notificationService, objectMapper,
                new WorkflowNodeHandlerRegistry(List.of(mockApproval)));

        WorkflowInstanceEntity inst = instance("{}");
        WorkflowEngine.NodeResult r = local.executeGraphFrom(
                inst, List.of(node("n1", "APPROVAL", Map.of())), List.of(), "n1", null);

        // 走 handler → CONTINUE;若退回 legacy 则会返回 NEEDS_APPROVAL 并创建 task
        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r,
                "内置类型必须优先走 registry handler,而不是 legacy 分支");
        verify(mockApproval).execute(any(NodeExecutionContext.class));
        verify(taskRepository, never()).save(any(WorkflowTaskEntity.class));
    }

    // ------------------------------------------------------------------
    // 2. ApprovalNodeHandler:补齐 NOT NULL 字段
    // ------------------------------------------------------------------

    @Test
    void approvalHandler_setsNodeTypeAndCreatedAt() {
        WorkflowInstanceEntity inst = instance("{}");
        UUID assignee = UUID.randomUUID();

        WorkflowEngine.NodeResult r = engine.executeGraphFrom(
                inst, List.of(node("n1", "APPROVAL", Map.of())), List.of(), "n1", assignee);

        assertEquals(WorkflowEngine.NodeResult.NEEDS_APPROVAL, r);

        ArgumentCaptor<WorkflowTaskEntity> captor = ArgumentCaptor.forClass(WorkflowTaskEntity.class);
        verify(taskRepository).save(captor.capture());
        WorkflowTaskEntity saved = captor.getValue();

        // 这两列在实体上是 nullable=false,漏设会触发约束冲突
        assertEquals("APPROVAL", saved.getNodeType(), "nodeType 为 NOT NULL,handler 必须设置");
        assertNotNull(saved.getCreatedAt(), "createdAt 为 NOT NULL,handler 必须设置");
        assertEquals(assignee, saved.getAssignee());
    }

    // ------------------------------------------------------------------
    // 3. NotificationNodeHandler:站内信 + 多渠道
    // ------------------------------------------------------------------

    @Test
    void notificationHandler_writesInAppMessageAndFiresChannels() {
        UUID creator = UUID.randomUUID();
        WorkflowEntity wf = mock(WorkflowEntity.class);
        when(wf.getCreatedBy()).thenReturn(creator);
        when(workflowRepository.findById(any(UUID.class))).thenReturn(Optional.of(wf));

        WorkflowInstanceEntity inst = instance("{}");
        Map<String, Object> cfg = Map.of("title", "申请已提交", "body", "您的请假申请已进入审批");

        engine.executeGraphFrom(inst, List.of(node("n1", "NOTIFICATION", cfg)), List.of(), "n1", null);

        // ① 站内信
        ArgumentCaptor<MessageEntity> msgCaptor = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageRepository).save(msgCaptor.capture());
        MessageEntity msg = msgCaptor.getValue();
        assertEquals(creator, msg.getRecipient());
        assertEquals("申请已提交", msg.getTitle());
        assertEquals("您的请假申请已进入审批", msg.getBody());

        // ② 多渠道推送
        verify(notificationService).fire(eq("tenant_default"), eq("workflow.notification"),
                eq(creator.toString()), any(Map.class));
    }

    // ------------------------------------------------------------------
    // 4. HttpNodeHandler:鉴权 + method 大写化
    // ------------------------------------------------------------------

    @Test
    void httpHandler_appliesBearerAuthAndUppercasesMethod() {
        when(httpRestTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                eq(String.class))).thenReturn(ResponseEntity.ok("ok"));

        Map<String, Object> cfg = Map.of(
                "method", "get",                                  // 小写,必须被大写化
                "url", "https://example.com/api",
                "auth", Map.of("type", "bearer", "token", "tok123"));

        engine.executeGraphFrom(instance("{}"), List.of(node("n1", "HTTP", cfg)), List.of(), "n1", null);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(httpRestTemplate).exchange(eq("https://example.com/api"), eq(HttpMethod.GET),
                captor.capture(), eq(String.class));

        // 鉴权头必须生效(legacy 支持,handler 初版完全没读 auth)
        assertEquals("Bearer tok123",
                captor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void httpHandler_appliesBasicAuth() {
        when(httpRestTemplate.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                eq(String.class))).thenReturn(ResponseEntity.ok("ok"));

        Map<String, Object> cfg = Map.of(
                "method", "POST",
                "url", "https://example.com/hook",
                "auth", Map.of("type", "basic", "username", "u1", "password", "p1"));

        engine.executeGraphFrom(instance("{}"), List.of(node("n1", "HTTP", cfg)), List.of(), "n1", null);

        ArgumentCaptor<HttpEntity> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(httpRestTemplate).exchange(eq("https://example.com/hook"), eq(HttpMethod.POST),
                captor.capture(), eq(String.class));
        assertNotNull(captor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION),
                "basic 鉴权必须写入 Authorization 头");
    }

    // ------------------------------------------------------------------
    // 5. ConditionNodeHandler:contains 操作符
    // ------------------------------------------------------------------

    @Test
    void conditionHandler_supportsContainsOperator() {
        WorkflowInstanceEntity inst = instance("{\"name\":\"hello world\"}");
        Map<String, Object> node = node("n1", "CONDITION",
                Map.of("when", Map.of("field", "name", "op", "contains", "value", "world")));

        NodeOutcome out = new ConditionNodeHandler()
                .execute(new NodeExecutionContext(inst, node, null, null));

        assertEquals(NodeOutcome.CONTINUE, out);
        // contains 是 legacy matchCondition 支持的 5 个 op 之一,handler 初版漏了
        assertEquals(Boolean.TRUE, node.get("_matched"),
                "contains 命中时必须标记 _matched=true");
    }

    @Test
    void conditionHandler_containsMissMarksFalse() {
        WorkflowInstanceEntity inst = instance("{\"name\":\"hello\"}");
        Map<String, Object> node = node("n1", "CONDITION",
                Map.of("when", Map.of("field", "name", "op", "contains", "value", "zzz")));

        new ConditionNodeHandler().execute(new NodeExecutionContext(inst, node, null, null));

        assertEquals(Boolean.FALSE, node.get("_matched"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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
}
