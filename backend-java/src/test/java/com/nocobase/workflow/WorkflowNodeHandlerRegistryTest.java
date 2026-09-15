package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WorkflowNodeHandlerRegistry 单元测试(Week 41 D4b.1).
 *
 * <p>覆盖 type 索引 + 大小写不敏感 + 重复注册报错。
 */
class WorkflowNodeHandlerRegistryTest {

    @Test
    void find_caseInsensitive() {
        // mock handler
        WorkflowNodeHandler handler = new WorkflowNodeHandler() {
            public String type() { return "APPROVAL"; }
            public NodeOutcome execute(NodeExecutionContext ctx) { return NodeOutcome.CONTINUE; }
        };
        WorkflowNodeHandlerRegistry registry = new WorkflowNodeHandlerRegistry(List.of(handler));

        assertThat(registry.find("APPROVAL")).isPresent();
        assertThat(registry.find("approval")).isPresent();
        assertThat(registry.find("Approval")).isPresent();
        assertThat(registry.find("NOTIFICATION")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void registeredTypes_listsAll() {
        WorkflowNodeHandler h1 = makeHandler("APPROVAL");
        WorkflowNodeHandler h2 = makeHandler("NOTIFICATION");
        WorkflowNodeHandler h3 = makeHandler("CONDITION");
        WorkflowNodeHandlerRegistry registry = new WorkflowNodeHandlerRegistry(List.of(h1, h2, h3));

        assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("APPROVAL", "NOTIFICATION", "CONDITION");
    }

    @Test
    void duplicateType_throws() {
        WorkflowNodeHandler h1 = makeHandler("APPROVAL");
        WorkflowNodeHandler h2 = makeHandler("APPROVAL");
        assertThatThrownBy(() -> new WorkflowNodeHandlerRegistry(List.of(h1, h2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPROVAL");
    }

    @Test
    void emptyRegistry_findsNothing() {
        WorkflowNodeHandlerRegistry registry = new WorkflowNodeHandlerRegistry(List.of());
        assertThat(registry.find("APPROVAL")).isEmpty();
        assertThat(registry.registeredTypes()).isEmpty();
    }

    private static WorkflowNodeHandler makeHandler(String type) {
        return new WorkflowNodeHandler() {
            public String type() { return type; }
            public NodeOutcome execute(NodeExecutionContext ctx) { return NodeOutcome.CONTINUE; }
        };
    }
}
