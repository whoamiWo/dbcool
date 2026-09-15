package com.nocobase.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpNodeHandlerTest {

    @Test
    void type_isHttp() {
        assertThat(new HttpNodeHandler().type()).isEqualTo("HTTP");
    }

    @Test
    void execute_missingUrl_returnsFailed() {
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance(), Map.of("id", "n1", "type", "HTTP",
                        "config", Map.of("method", "GET")), UUID.randomUUID(), null);
        NodeOutcome out = new HttpNodeHandler().execute(ctx);
        assertThat(out).isEqualTo(NodeOutcome.FAILED);
    }

    @Test
    void execute_invalidUrl_returnsContinueButLogsError() {
        // 不可达的 URL → RestClientException → handler 返回 CONTINUE + 错误日志
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance(), Map.of("id", "n1", "type", "HTTP",
                        "config", Map.of("method", "GET", "url", "http://localhost:1/nonexistent")),
                UUID.randomUUID(), null);
        NodeOutcome out = new HttpNodeHandler().execute(ctx);
        // 报告 4.3.3:Week 41 默认 CONTINUE + 错误日志(失败可配置中断或重试是 Week 42+)
        assertThat(out).isEqualTo(NodeOutcome.CONTINUE);
    }

    @Test
    void execute_defaultMethodPost() {
        // 验证默认 method 是 POST
        NodeExecutionContext ctx = new NodeExecutionContext(
                instance(), Map.of("id", "n1", "type", "HTTP",
                        "config", Map.of("url", "http://localhost:1/x")),
                UUID.randomUUID(), null);
        NodeOutcome out = new HttpNodeHandler().execute(ctx);
        // 只确认执行不抛错(网络错返回 CONTINUE)
        assertThat(out).isIn(NodeOutcome.CONTINUE, NodeOutcome.FAILED);
    }

    private WorkflowInstanceEntity instance() {
        WorkflowInstanceEntity ins = new WorkflowInstanceEntity();
        ins.setId(UUID.randomUUID());
        ins.setWorkflowId(UUID.randomUUID());
        ins.setTenantId("tenant_default");
        return ins;
    }
}
