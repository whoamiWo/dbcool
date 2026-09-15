package com.nocobase.workflow.handler;

import com.nocobase.workflow.NodeExecutionContext;
import com.nocobase.workflow.NodeOutcome;
import com.nocobase.workflow.WorkflowNodeHandler;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConditionNodeHandler implements WorkflowNodeHandler {

    private static final Logger log = LoggerFactory.getLogger(ConditionNodeHandler.class);

    @Override
    public String type() {
        return "CONDITION";
    }

    @Override
    public NodeOutcome execute(NodeExecutionContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) ctx.node().getOrDefault("config", Map.of());
        Object when = config.get("when");
        boolean matched = when == null ? true : evaluate(when, ctx);

        // matched 用 ctx.extra 传递分支(Week 41 简化)
        log.info("[workflow {} node {}] CONDITION matched={}",
                ctx.instance().getId(), ctx.node().get("id"), matched);
        // CONDITION 节点不直接决定 CONTINUE/FAILED — 留给 WorkflowEngine 根据 sourceHandle 选分支
        // matched=true → engine 选 sourceHandle="true" 的边
        // matched=false → engine 选 sourceHandle="false" 的边
        // 这里通过 return SKIPPED 让 engine 知道这是分支节点(实际语义由 handleHolder 表达)
        ctx.node().put("_matched", matched); // 引擎读这个
        return NodeOutcome.CONTINUE;
    }

    /** Week 41 简化评估:eq/neq/gt/lt。Week 42+ 接 Aviator 表达式引擎(报告 4.3.2)。 */
    @SuppressWarnings("unchecked")
    private boolean evaluate(Object when, NodeExecutionContext ctx) {
        if (!(when instanceof Map)) return true;
        Map<String, Object> w = (Map<String, Object>) when;
        String field = (String) w.get("field");
        String op = (String) w.getOrDefault("op", "eq");
        Object expected = w.get("value");

        // 从 triggerDataJson 取实际值 — 简化为 Object equality
        Object actual = extractValue(ctx, field);
        if (actual == null) return false;
        try {
            switch (op) {
                case "eq": return String.valueOf(actual).equals(String.valueOf(expected));
                case "neq": return !String.valueOf(actual).equals(String.valueOf(expected));
                case "gt": return Double.parseDouble(actual.toString()) > Double.parseDouble(expected.toString());
                case "lt": return Double.parseDouble(actual.toString()) < Double.parseDouble(expected.toString());
                default: return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private Object extractValue(NodeExecutionContext ctx, String field) {
        if (field == null) return null;
        // 简化为从 triggerDataJson 解析查找
        String json = ctx.instance().getTriggerDataJson();
        if (json == null || json.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> typeRef =
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {};
            Map<String, Object> data = mapper.readValue(json, typeRef);
            // 支持嵌套字段(如 "data.amount")
            String[] parts = field.split("\\.");
            Object cur = data;
            for (String p : parts) {
                if (cur instanceof Map) cur = ((Map<String, Object>) cur).get(p);
                else return null;
            }
            return cur;
        } catch (Exception e) {
            return null;
        }
    }
}
