package com.nocobase.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nocobase.notification.NotificationService;
import com.nocobase.workflow.MessageRepository;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import com.nocobase.workflow.WorkflowRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationNodeHandlerTest {

    /**
     * Week 41 复核:NotificationNodeHandler 现已依赖 WorkflowRepository /
     * MessageRepository / NotificationService(写站内信 + 多渠道推送),构造需传入 mock。
     *
     * <p>三个 mock 均返回默认值 → 解析不出收件人 → 走跳过分支返回 CONTINUE,
     * 与改造前"仅日志"的返回值一致,故原断言不变。
     */
    private NotificationNodeHandler handler() {
        return new NotificationNodeHandler(
                mock(WorkflowRepository.class),
                mock(MessageRepository.class),
                mock(NotificationService.class));
    }

    @Test
    void type_isNotification() {
        assertThat(handler().type()).isEqualTo("NOTIFICATION");
    }

    @Test
    void execute_withTitleAndBody_returnsContinue() {
        WorkflowInstanceEntity ins = instance();
        NodeExecutionContext ctx = new NodeExecutionContext(
                ins,
                Map.of("id", "n1", "type", "NOTIFICATION",
                        "config", Map.of("title", "测试标题", "body", "测试内容")),
                UUID.randomUUID(),
                null);
        NodeOutcome out = handler().execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
    }

    @Test
    void execute_emptyConfig_usesDefaults() {
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance(),
                Map.of("id", "n1", "type", "NOTIFICATION", "config", Map.of()),
                UUID.randomUUID(), null);
        NodeOutcome out = handler().execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
    }

    @Test
    void execute_messageAliasAccepted() {
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance(),
                Map.of("id", "n1", "type", "NOTIFICATION",
                        "config", Map.of("title", "T", "message", "via message key")),
                UUID.randomUUID(), null);
        NodeOutcome out = handler().execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
    }

    private WorkflowInstanceEntity instance() {
        WorkflowInstanceEntity ins = new WorkflowInstanceEntity();
        ins.setId(UUID.randomUUID());
        ins.setWorkflowId(UUID.randomUUID());
        ins.setTenantId("tenant_default");
        return ins;
    }
}
