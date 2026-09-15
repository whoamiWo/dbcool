package com.nocobase.workflow.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.common.ExpressionEvaluator;
import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 条件节点 handler。
 *
 * <p>Week 41 复核 D4b.4:接入 Aviator 表达式引擎,替换此前只能做单字段比较的
 * 简化评估(eq/neq/gt/lt/contains),现在支持 {@code &&} / {@code ||}、
 * 算术、函数调用等完整表达式。
 */
@Component
public class ConditionNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ConditionNodeHandler.class);

    private final ExpressionEvaluator evaluator;

    /** 兼容既有测试的无参构造(表达式引擎无状态,自建实例即可)。 */
    public ConditionNodeHandler() {
        this(new ExpressionEvaluator());
    }

    @Autowired
    public ConditionNodeHandler(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public String type() {
        return "CONDITION";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());

        boolean matched = evaluateCondition(config, ctx);

        log.info("[workflow {} node {}] CONDITION matched={}",
                ctx.instance().getId(), ctx.node().get("id"), matched);
        // 分支结果回传给引擎:图模式据此选 sourceHandle="true" / "false" 的边
        ctx.node().put("_matched", matched);
        return NodeOutcome.CONTINUE;
    }

    /**
     * 两种判定方式,表达式优先、结构化 {@code when} 兜底(向后兼容既有模板):
     * <ol>
     *   <li>{@code config.expression} — Aviator 表达式,
     *       如 {@code amount > 1000 && status == 'new'}</li>
     *   <li>{@code config.when = {field, op, value}} — 结构化比较
     *       (eq / neq / gt / lt / contains),支持点分嵌套路径</li>
     * </ol>
     */
    private boolean evaluateCondition(Map<String, Object> config, NodeExecutionContext ctx) {
        Object expr = config.get("expression");
        if (expr instanceof String s && !s.isBlank()) {
            return evaluator.evaluateBoolean(s, parseTriggerData(ctx));
        }
        Object when = config.get("when");
        return when == null || evaluate(when, ctx);
    }

    @SuppressWarnings("unchecked")
    private boolean evaluate(Object when, NodeExecutionContext ctx) {
        if (!(when instanceof Map)) return true;
        Map<String, Object> w = (Map<String, Object>) when;
        String field = (String) w.get("field");
        String op = (String) w.getOrDefault("op", "eq");
        Object expected = w.get("value");

        Object actual = extractValue(ctx, field);
        if (actual == null) return false;
        try {
            switch (op) {
                case "eq": return String.valueOf(actual).equals(String.valueOf(expected));
                case "neq": return !String.valueOf(actual).equals(String.valueOf(expected));
                case "gt": return Double.parseDouble(actual.toString()) > Double.parseDouble(expected.toString());
                case "lt": return Double.parseDouble(actual.toString()) < Double.parseDouble(expected.toString());
                case "contains": return String.valueOf(actual).contains(String.valueOf(expected));
                default: return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 triggerData 为 Map,作为表达式求值的变量环境。 */
    private Map<String, Object> parseTriggerData(NodeExecutionContext ctx) {
        String json = ctx.instance().getTriggerDataJson();
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[workflow {}] triggerData 解析失败,表达式环境为空", ctx.instance().getId());
            return Map.of();
        }
    }

    /** 按点分路径取值(如 {@code data.amount})。 */
    private Object extractValue(NodeExecutionContext ctx, String field) {
        if (field == null) return null;
        Map<String, Object> data = parseTriggerData(ctx);
        Object cur = data;
        for (String p : field.split("\\.")) {
            if (cur instanceof Map) cur = ((Map<String, Object>) cur).get(p);
            else return null;
        }
        return cur;
    }
}
