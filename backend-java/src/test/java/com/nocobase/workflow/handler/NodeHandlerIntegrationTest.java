package com.nocobase.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nocobase.notification.NotificationService;
import com.nocobase.workflow.MessageRepository;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowInstanceRepository;
import com.nocobase.workflow.WorkflowNodeHandler;
import com.nocobase.workflow.WorkflowNodeHandlerRegistry;
import com.nocobase.workflow.WorkflowRepository;
import com.nocobase.workflow.WorkflowTaskRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Handler + Registry 集成测试(Week 41 D4b.1).
 */
class NodeHandlerIntegrationTest {

    private WorkflowInstanceRepository instanceRepository;
    private WorkflowTaskRepository taskRepository;
    private WorkflowRepository workflowRepository;
    private MessageRepository messageRepository;
    private NotificationService notificationService;
    private WorkflowNodeHandlerRegistry registry;
    private WorkflowInstanceEntity instance;
    private UUID assignee;

    @BeforeEach
    void setUp() {
        instanceRepository = mock(WorkflowInstanceRepository.class);
        taskRepository = mock(WorkflowTaskRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        messageRepository = mock(MessageRepository.class);
        notificationService = mock(NotificationService.class);
        when(instanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 显式声明:未 stub 时解析不出收件人 → 通知节点走跳过分支返回 CONTINUE
        when(workflowRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        registry = new WorkflowNodeHandlerRegistry(List.of(
                new ApprovalNodeHandler(instanceRepository, taskRepository),
                // Week 41 复核:通知 handler 需注入站内信与多渠道依赖
                new NotificationNodeHandler(workflowRepository, messageRepository, notificationService),
                new ConditionNodeHandler(),
                new HttpNodeHandler()
        ));

        instance = new WorkflowInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setWorkflowId(UUID.randomUUID());
        instance.setTenantId("tenant_default");
        instance.setTriggerDataJson("{\"data\":{\"amount\":1500}}");
        assignee = UUID.randomUUID();
    }

    @Test
    void allFourHandlers_registered() {
        assertThat(registry.registeredTypes()).containsExactlyInAnyOrder(
                "APPROVAL", "NOTIFICATION", "CONDITION", "HTTP");
    }

    @Test
    void approvalHandler_createsTaskAndReturnsNeedsApproval() {
        WorkflowNodeHandler h = registry.find("APPROVAL").orElseThrow();
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance, Map.of("id", "n1", "type", "APPROVAL",
                        "config", Map.of("assignee", assignee.toString())),
                assignee, null);
        NodeOutcome out = h.execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.NEEDS_APPROVAL);
    }

    @Test
    void notificationHandler_returnsContinue() {
        WorkflowNodeHandler h = registry.find("NOTIFICATION").orElseThrow();
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance, Map.of("id", "n1", "type", "NOTIFICATION",
                        "config", Map.of("title", "T", "body", "B")),
                assignee, null);
        NodeOutcome out = h.execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
    }

    @Test
    void conditionHandler_matchedTrue_writesMatchedFlag() {
        WorkflowNodeHandler h = registry.find("CONDITION").orElseThrow();
        Map<String, Object> node = new java.util.HashMap<>();
        node.put("id", "n1");
        node.put("type", "CONDITION");
        node.put("config", Map.of("when", Map.of("field", "data.amount", "op", "gt", "value", 1000)));
        NodeExecutionContext ctx = new NodeExecutionContext(instance, node, assignee, null);
        h.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void conditionHandler_matchedFalse() {
        WorkflowNodeHandler h = registry.find("CONDITION").orElseThrow();
        Map<String, Object> node = new java.util.HashMap<>();
        node.put("id", "n1");
        node.put("type", "CONDITION");
        node.put("config", Map.of("when", Map.of("field", "data.amount", "op", "lt", "value", 1000)));
        NodeExecutionContext ctx = new NodeExecutionContext(instance, node, assignee, null);
        h.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(Boolean.FALSE);
    }

    @Test
    void httpHandler_missingUrl_returnsFailed() {
        WorkflowNodeHandler h = registry.find("HTTP").orElseThrow();
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance, Map.of("id", "n1", "type", "HTTP",
                        "config", Map.of("method", "GET")),
                assignee, null);
        NodeOutcome out = h.execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.FAILED);
    }
}
