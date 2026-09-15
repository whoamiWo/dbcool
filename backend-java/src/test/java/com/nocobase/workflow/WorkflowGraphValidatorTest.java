package com.nocobase.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowGraphValidatorTest {

    private final WorkflowGraphValidator validator = new WorkflowGraphValidator();

    private static Map<String, Object> node(String id) {
        return Map.of("id", id, "type", "NOTIFICATION");
    }

    private static Map<String, Object> edge(String src, String tgt) {
        return Map.of("source", src, "target", tgt);
    }

    @Test
    void emptyNodes_passes() {
        assertThatCode(() -> validator.validate(List.of(), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void linearChain_passes() {
        List<Map<String, Object>> nodes = List.of(node("n1"), node("n2"), node("n3"));
        List<Map<String, Object>> edges = List.of(edge("n1", "n2"), edge("n2", "n3"));
        assertThatCode(() -> validator.validate(nodes, edges)).doesNotThrowAnyException();
    }

    @Test
    void branching_passes() {
        // n1 → n2, n1 → n3(条件分支)
        List<Map<String, Object>> nodes = List.of(node("n1"), node("n2"), node("n3"));
        List<Map<String, Object>> edges = List.of(edge("n1", "n2"), edge("n1", "n3"));
        assertThatCode(() -> validator.validate(nodes, edges)).doesNotThrowAnyException();
    }

    @Test
    void selfLoop_rejected() {
        // n1 → n1
        List<Map<String, Object>> nodes = List.of(node("n1"));
        List<Map<String, Object>> edges = List.of(edge("n1", "n1"));
        assertThatThrownBy(() -> validator.validate(nodes, edges))
                .isInstanceOf(WorkflowGraphValidationException.class)
                .hasMessageContaining("环");
    }

    @Test
    void cycleOfThree_rejected() {
        // n1 → n2 → n3 → n1
        List<Map<String, Object>> nodes = List.of(node("n1"), node("n2"), node("n3"));
        List<Map<String, Object>> edges = List.of(
                edge("n1", "n2"), edge("n2", "n3"), edge("n3", "n1"));
        assertThatThrownBy(() -> validator.validate(nodes, edges))
                .isInstanceOf(WorkflowGraphValidationException.class)
                .hasMessageContaining("环");
    }

    @Test
    void cycleThroughBackEdge_rejected() {
        // n1 → n2 → n3, n3 → n1(回边)
        List<Map<String, Object>> nodes = List.of(node("n1"), node("n2"), node("n3"));
        List<Map<String, Object>> edges = List.of(
                edge("n1", "n2"), edge("n2", "n3"), edge("n3", "n1"));
        assertThatThrownBy(() -> validator.validate(nodes, edges))
                .isInstanceOf(WorkflowGraphValidationException.class);
    }

    @Test
    void danglingEdge_rejected() {
        List<Map<String, Object>> nodes = List.of(node("n1"));
        List<Map<String, Object>> edges = List.of(edge("n1", "ghost"));
        assertThatThrownBy(() -> validator.validate(nodes, edges))
                .isInstanceOf(WorkflowGraphValidationException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void tooManyNodes_rejected() {
        // 201 nodes > MAX_NODES (200)
        java.util.List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < WorkflowGraphValidator.MAX_NODES + 1; i++) {
            nodes.add(node("n" + i));
        }
        assertThatThrownBy(() -> validator.validate(nodes, List.of()))
                .isInstanceOf(WorkflowGraphValidationException.class)
                .hasMessageContaining("节点数超过上限");
    }

    @Test
    void maxNodesExactly_passes() {
        java.util.List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        for (int i = 0; i < WorkflowGraphValidator.MAX_NODES; i++) {
            nodes.add(node("n" + i));
        }
        // 无边,纯节点列表应通过
        assertThatCode(() -> validator.validate(nodes, List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void nullEdges_passes() {
        assertThatCode(() -> validator.validate(List.of(node("n1")), null))
                .doesNotThrowAnyException();
    }

    @Test
    void disconnectedComponent_passes() {
        // n1 → n2,n3 孤立(无环)
        List<Map<String, Object>> nodes = List.of(node("n1"), node("n2"), node("n3"));
        List<Map<String, Object>> edges = List.of(edge("n1", "n2"));
        assertThatCode(() -> validator.validate(nodes, edges)).doesNotThrowAnyException();
    }

    @Test
    void diamondPattern_passes() {
        // n1 → n2, n1 → n3, n2 → n4, n3 → n4(菱形,无环)
        List<Map<String, Object>> nodes = List.of(node("n1"), node("n2"), node("n3"), node("n4"));
        List<Map<String, Object>> edges = List.of(
                edge("n1", "n2"), edge("n1", "n3"),
                edge("n2", "n4"), edge("n3", "n4"));
        assertThatCode(() -> validator.validate(nodes, edges)).doesNotThrowAnyException();
    }
}
