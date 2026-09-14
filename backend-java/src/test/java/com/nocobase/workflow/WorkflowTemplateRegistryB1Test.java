package com.nocobase.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nocobase.notification.NotificationService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * B1 修复回归测试(Week 40)—— 验证:
 * 1. WorkflowEngine 大小写不敏感识别节点类型(template 内部约定统一为大写,但防御性兼容小写)
 * 2. WorkflowTemplateRegistry 3 个内置模板的节点类型大写、与引擎匹配
 * 3. condition 节点 config 格式为 {when: {field, op, value}},与 matchCondition 入参一致
 * 4. 装模板产生的 NOTIFICATION 节点能真正执行(写 message + fire notification),
 *    不再被静默跳过(unknown node type 警告)
 *
 * 验收:
 *   - 安装 3 个内置模板后触发,实例状态非"空执行"
 *   - "unknown node type" 警告日志不再出现
 *   - condition 节点按 when 配置比较 triggerData
 */
class WorkflowTemplateRegistryB1Test {

    private WorkflowTemplateRegistry registry;
    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowRepository workflowRepository;
    private MessageRepository messageRepository;
    private NotificationService notificationService;
    private com.fasterxml.jackson.databind.ObjectMapper realMapper;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        registry = new WorkflowTemplateRegistry();
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        messageRepository = mock(MessageRepository.class);
        notificationService = mock(NotificationService.class);
        realMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        engine = new WorkflowEngine(instanceRepository, taskRepository, workflowRepository,
                messageRepository, notificationService, realMapper);
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ============ 模板本身:节点类型必须是大写、condition config 必须是 when ============

    @Test
    void leaveApproval_template_usesUppercaseNodeTypes() {
        WorkflowTemplate tpl = registry.get("leave_approval").orElseThrow();
        List<Map<String, Object>> nodes = tpl.workflow().nodes();
        // 模板无 start 节点(已删),只有 notify
        assertEquals(1, nodes.size());
        assertEquals("NOTIFICATION", nodes.get(0).get("type"));
    }

    @Test
    void expenseReport_template_conditionNodeUsesWhenShape() {
        WorkflowTemplate tpl = registry.get("expense_report").orElseThrow();
        List<Map<String, Object>> nodes = tpl.workflow().nodes();
        Map<String, Object> review = nodes.stream()
                .filter(n -> "CONDITION".equals(n.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) review.get("config");
        assertTrue(config.containsKey("when"), "condition node config 必须用 'when' key");
        @SuppressWarnings("unchecked")
        Map<String, Object> when = (Map<String, Object>) config.get("when");
        assertEquals("amount", when.get("field"));
        assertEquals("gt", when.get("op"));
        assertEquals(1000, when.get("value"));
    }

    @Test
    void customerFollowup_template_usesUppercaseNodeTypes() {
        WorkflowTemplate tpl = registry.get("customer_followup").orElseThrow();
        List<Map<String, Object>> nodes = tpl.workflow().nodes();
        assertEquals(1, nodes.size());
        assertEquals("NOTIFICATION", nodes.get(0).get("type"));
    }

    @Test
    void allTemplates_noLowercaseNodeTypes() {
        // 防御性扫描:所有内置模板不应包含小写节点类型常量
        for (WorkflowTemplate tpl : registry.list()) {
            for (Map<String, Object> n : tpl.workflow().nodes()) {
                String type = (String) n.get("type");
                if (type == null) continue;
                if ("manual".equals(type) || "notification".equals(type)
                        || "condition".equals(type) || "http".equals(type)) {
                    throw new AssertionError(String.format(
                            "模板 %s 节点 %s 用了小写类型 '%s'",
                            tpl.key(), n.get("id"), type));
                }
            }
        }
    }

    // ============ 引擎大小写不敏感 ============

    @Test
    void engine_lowercaseNotificationStillExecutes() {
        // 模拟外部系统(API)塞了 lowercase 类型的情况,防御性兼容
        WorkflowInstanceEntity ins = instanceWithTrigger("{}");
        UUID createdBy = UUID.randomUUID();
        stubNotificationRecipient(ins, createdBy);

        // lowercase!
        List<Map<String, Object>> nodes = List.of(
                node("n1", "notification", Map.of("title", "T", "message", "B")));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        // 不再返回 CONTINUE 然后静默被跳过,而是真正执行 NOTIFICATION
        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, times(1)).save(any());
        verify(notificationService, times(1)).fire(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void engine_uppercaseNotificationStillExecutes() {
        WorkflowInstanceEntity ins = instanceWithTrigger("{}");
        UUID createdBy = UUID.randomUUID();
        stubNotificationRecipient(ins, createdBy);

        List<Map<String, Object>> nodes = List.of(
                node("n1", "NOTIFICATION", Map.of("title", "T", "message", "B")));

        WorkflowEngine.NodeResult r = engine.executeFrom(ins, nodes, 0, null);

        assertEquals(WorkflowEngine.NodeResult.CONTINUE, r);
        verify(messageRepository, times(1)).save(any());
        verify(notificationService, times(1)).fire(
                anyString(), anyString(), anyString(), any());
    }

    // ============ helpers ============

    private WorkflowInstanceEntity instanceWithTrigger(String triggerJson) {
        WorkflowInstanceEntity ins = new WorkflowInstanceEntity();
        ins.setId(UUID.randomUUID());
        ins.setWorkflowId(UUID.randomUUID());
        ins.setTenantId("tenant_default");
        ins.setTriggerDataJson(triggerJson);
        ins.setCurrentNodeIndex(0);
        return ins;
    }

    private void stubNotificationRecipient(WorkflowInstanceEntity ins, UUID createdBy) {
        when(instanceRepository.findById(ins.getId())).thenReturn(Optional.of(ins));
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(ins.getWorkflowId());
        wf.setCreatedBy(createdBy);
        when(workflowRepository.findById(ins.getWorkflowId())).thenReturn(Optional.of(wf));
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> n = new java.util.LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        if (config != null) n.put("config", config);
        return n;
    }
}
