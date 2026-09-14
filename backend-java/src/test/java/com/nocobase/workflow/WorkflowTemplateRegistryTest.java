package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * WorkflowTemplateRegistry 单元测试(Week 21 抬红线).
 *
 * <p>3 个内置模板,验证 list + get 行为。
 */
class WorkflowTemplateRegistryTest {

    private final WorkflowTemplateRegistry registry = new WorkflowTemplateRegistry();

    @Test
    void list_returnsThreeTemplates() {
        var all = registry.list();
        assertThat(all).hasSize(3);
    }

    @Test
    void list_containsExpectedKeys() {
        var keys = registry.list().stream().map(WorkflowTemplate::key).toList();
        assertThat(keys).containsExactly("leave_approval", "expense_report", "customer_followup");
    }

    @Test
    void list_returnsImmutableView() {
        var all = registry.list();
        // List.copyOf → immutable
        assertThatThrownBy(() -> all.add(new WorkflowTemplate("x", "X", "C", "D", "I", null, null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void get_existingKey_returnsTemplate() {
        Optional<WorkflowTemplate> t = registry.get("leave_approval");
        assertThat(t).isPresent();
        assertThat(t.get().key()).isEqualTo("leave_approval");
        assertThat(t.get().name()).isEqualTo("请假审批");
        assertThat(t.get().category()).isEqualTo("HR");
    }

    @Test
    void get_unknownKey_returnsEmpty() {
        assertThat(registry.get("nonexistent_template")).isEmpty();
    }

    @Test
    void get_nullKey_returnsEmpty() {
        assertThat(registry.get(null)).isEmpty();
    }

    /* === 模板内容合理性 === */

    @Test
    void leaveApproval_hasLeaveRequestCollection() {
        var t = registry.get("leave_approval").orElseThrow();
        assertThat(t.collections()).hasSize(1);
        var col = t.collections().get(0);
        assertThat(col.name()).isEqualTo("leave_request");
        assertThat(col.fields()).extracting(f -> f.get("name"))
                .contains("title", "days", "reason", "status", "applicant");
    }

    @Test
    void expenseReport_hasConditionNode() {
        var t = registry.get("expense_report").orElseThrow();
        var wf = t.workflow();
        assertThat(wf).isNotNull();
        assertThat(wf.nodes()).anyMatch(n -> "condition".equals(n.get("type")));
    }

    @Test
    void customerFollowup_hasNotificationNode() {
        var t = registry.get("customer_followup").orElseThrow();
        var wf = t.workflow();
        assertThat(wf.nodes()).anyMatch(n -> "notification".equals(n.get("type")));
    }

    @Test
    void allTemplates_haveTrigger() {
        for (WorkflowTemplate t : registry.list()) {
            assertThat(t.workflow().trigger())
                .as("template %s should have trigger", t.key())
                .isNotNull()
                .containsKey("type");
        }
    }

    @Test
    void allTemplates_haveEdgesAndNodesBalanced() {
        // 每个 edge.source 必须对应到一个 node.id(target 可以是虚拟终态 "end")
        for (WorkflowTemplate t : registry.list()) {
            var wf = t.workflow();
            for (var edge : wf.edges()) {
                String source = (String) edge.get("source");
                String target = (String) edge.get("target");
                assertThat(wf.nodes())
                    .as("template %s edge %s->%s: source must be a node", t.key(), source, target)
                    .anyMatch(n -> source.equals(n.get("id")));
                // target 可能是 "end" 虚拟节点,或者也是真实节点
                // 不强求(target 设计上是 next 链的终点)
                if (!"end".equals(target)) {
                    assertThat(wf.nodes())
                        .as("template %s edge %s->%s: target must be a real node", t.key(), source, target)
                        .anyMatch(n -> target.equals(n.get("id")));
                }
            }
        }
    }
}