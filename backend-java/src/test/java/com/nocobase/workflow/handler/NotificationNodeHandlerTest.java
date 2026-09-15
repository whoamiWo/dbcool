package com.nocobase.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationNodeHandlerTest {

    @Test
    void type_isNotification() {
        assertThat(new NotificationNodeHandler().type()).isEqualTo("NOTIFICATION");
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
        NodeOutcome out = new NotificationNodeHandler().execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
    }

    @Test
    void execute_emptyConfig_usesDefaults() {
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance(),
                Map.of("id", "n1", "type", "NOTIFICATION", "config", Map.of()),
                UUID.randomUUID(), null);
        NodeOutcome out = new NotificationNodeHandler().execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
    }

    @Test
    void execute_messageAliasAccepted() {
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance(),
                Map.of("id", "n1", "type", "NOTIFICATION",
                        "config", Map.of("title", "T", "message", "via message key")),
                UUID.randomUUID(), null);
        NodeOutcome out = new NotificationNodeHandler().execute(ctx);
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
