package com.nocobase.workflow.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowInstanceEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ConditionNodeHandlerTest {

    private final ConditionNodeHandler handler = new ConditionNodeHandler();

    @Test
    void type_isCondition() {
        assertThat(handler.type()).isEqualTo("CONDITION");
    }

    @Test
    void execute_nullWhen_returnsTrueAndMatched() {
        WorkflowInstanceEntity ins = instance("{}");
        Map<String, Object> node = node("n1", Map.of());
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        NodeOutcome outcome = handler.execute(ctx);
        assertThat(outcome).isEqualTo(NodeOutcome.CONTINUE);
        assertThat(node.get("_matched")).isEqualTo(true);
    }

    @Test
    void evaluate_eq_matched() {
        WorkflowInstanceEntity ins = instance("{\"amount\":100}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "amount", "op", "eq", "value", 100)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(true);
    }

    @Test
    void evaluate_eq_notMatched() {
        WorkflowInstanceEntity ins = instance("{\"amount\":50}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "amount", "op", "eq", "value", 100)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(false);
    }

    @Test
    void evaluate_neq_matched() {
        WorkflowInstanceEntity ins = instance("{\"amount\":50}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "amount", "op", "neq", "value", 100)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(true);
    }

    @Test
    void evaluate_gt_matched() {
        WorkflowInstanceEntity ins = instance("{\"amount\":150}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "amount", "op", "gt", "value", 100)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(true);
    }

    @Test
    void evaluate_lt_matched() {
        WorkflowInstanceEntity ins = instance("{\"amount\":50}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "amount", "op", "lt", "value", 100)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(true);
    }

    @Test
    void evaluate_nestedField() {
        WorkflowInstanceEntity ins = instance("{\"data\":{\"price\":99}}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "data.price", "op", "lt", "value", 100)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(true);
    }

    @Test
    void evaluate_fieldMissing_returnsFalse() {
        WorkflowInstanceEntity ins = instance("{\"a\":1}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "missing", "op", "eq", "value", 1)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(false);
    }

    @Test
    void evaluate_nonMapWhen_returnsTrue() {
        WorkflowInstanceEntity ins = instance("{}");
        Map<String, Object> node = node("n1", Map.of("when", "always"));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(true);
    }

    @Test
    void evaluate_invalidNumber_returnsFalse() {
        WorkflowInstanceEntity ins = instance("{\"a\":\"x\"}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "a", "op", "gt", "value", 1)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(false);
    }

    @Test
    void evaluate_unknownOp_returnsFalse() {
        WorkflowInstanceEntity ins = instance("{\"a\":1}");
        Map<String, Object> node = node("n1", Map.of("when", Map.of("field", "a", "op", "in", "value", 1)));
        NodeExecutionContext ctx = new NodeExecutionContext(ins, node, UUID.randomUUID(), null);
        handler.execute(ctx);
        assertThat(node.get("_matched")).isEqualTo(false);
    }

    private WorkflowInstanceEntity instance(String json) {
        WorkflowInstanceEntity e = new WorkflowInstanceEntity();
        e.setId(UUID.randomUUID());
        e.setTriggerDataJson(json);
        e.setStatus(WorkflowInstanceEntity.Status.RUNNING);
        return e;
    }

    private Map<String, Object> node(String id, Map<String, Object> config) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", "CONDITION");
        n.put("config", config);
        return n;
    }
}
