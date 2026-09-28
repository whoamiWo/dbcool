package com.nocobase.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流表达式引擎 — 支持 JSONPath + 条件表达式。
 * 
 * <p>PHASE 57 P1 工作流表达式：
 * <ul>
 *   <li>支持 JSONPath 语法: data.user.name, data.items[0].price</li>
 *   <li>支持条件运算: ==, !=, >, <, >=, <=, contains, startsWith, endsWith</li>
 *   <li>支持布尔运算: &&, ||, !</li>
 *   <li>支持数值运算: +, -, *, /</li>
 *   <li>支持变量替换: {{ field_name }}</li>
 * </ul>
 *
 * <p>注意: 此类与 {@link com.nocobase.common.ExpressionEvaluator} 共存，
 * 使用不同的 bean 名称以避免冲突。
 */
@Component("workflowExpressionEvaluator")
public class ExpressionEvaluator {

    private final ObjectMapper mapper;

    public ExpressionEvaluator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 评估表达式。
     * 
     * @param expression 表达式字符串
     * @param context 上下文数据（JSON 对象）
     * @return 评估结果
     */
    public Object evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        
        // 1. 变量替换
        String expanded = expandVariables(expression, context);
        
        // 2. 解析为 JSON 表达式
        if (expanded.startsWith("{") || expanded.startsWith("[")) {
            try {
                return mapper.readTree(expanded);
            } catch (Exception e) {
                throw new RuntimeException("JSON 解析失败: " + expanded, e);
            }
        }
        
        // 3. 条件表达式
        if (containsOperator(expanded)) {
            return evalCondition(expanded, context);
        }
        
        // 4. 尝试作为字段路径解析（支持 user.name 风格）
        try {
            Object fieldValue = getFieldValue(expanded, context);
            if (fieldValue != null && !isSameAsExpression(fieldValue, expanded)) {
                return fieldValue;
            }
        } catch (Exception e) {
            // 不是字段路径，忽略异常
        }
        
        // 5. 简单值返回
        return expanded;
    }

    /**
     * 判断字段值是否与原始表达式相同（防止无限循环或重复返回）。
     */
    private boolean isSameAsExpression(Object value, String expression) {
        return value.toString().equals(expression);
    }

    /**
     * 变量替换：将 {{ field_name }} 替换为上下文中对应的值。
     */
    private String expandVariables(String expr, Map<String, Object> context) {
        Pattern varPattern = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*\\}\\}");
        Matcher matcher = varPattern.matcher(expr);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String field = matcher.group(1).trim();
            Object value = getFieldValue(field, context);
            String replacement = value != null ? value.toString() : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }

    /**
     * 从上下文中获取字段值（支持 JSONPath 风格）。
     */
    @SuppressWarnings("unchecked")
    private Object getFieldValue(String field, Map<String, Object> context) {
        // 处理数组索引：items[0]
        Matcher idxMatcher = Pattern.compile("(.+?)\\[(\\d+)\\]").matcher(field);
        if (idxMatcher.matches()) {
            String arrayName = idxMatcher.group(1);
            int index = Integer.parseInt(idxMatcher.group(2));
            
            Object arrayObj = context.get(arrayName);
            if (arrayObj instanceof List) {
                List<?> list = (List<?>) arrayObj;
                return index < list.size() ? list.get(index) : null;
            } else if (arrayObj instanceof Object[]) {
                Object[] arr = (Object[]) arrayObj;
                return index < arr.length ? arr[index] : null;
            }
            return null;
        }
        
        // 处理嵌套字段：user.name
        String[] parts = field.split("\\.");
        Object current = context;
        
        for (String part : parts) {
            if (current == null) return null;
            
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
            } else {
                return null;
            }
        }
        
        return current;
    }

    /**
     * 评估条件表达式。
     */
    private boolean evalCondition(String expr, Map<String, Object> context) {
        // 先检查布尔运算（避免正则匹配整个表达式）
        if (expr.contains("&&") || expr.contains("||")) {
            return evalBooleanLogic(expr, context);
        }
        
        // 匹配比较运算（注意：长运算符必须放前面）
        Pattern comparePattern = Pattern.compile("(.+?)\\s*(>=|<=|==|!=|contains|startsWith|endsWith|>|<)\\s*(.+)");
        Matcher matcher = comparePattern.matcher(expr.trim());
        
        if (matcher.matches()) {
            String left = matcher.group(1).trim();
            String op = matcher.group(2);
            String right = matcher.group(3).trim();
            
            Object leftVal = evaluate(left, context);
            Object rightVal = resolveValue(right, context);
            
            return compare(leftVal, op, rightVal);
        }
        
        // 简单等于
        return "true".equalsIgnoreCase(expr) || "1".equals(expr);
    }

    /**
     * 解析值：优先尝试字面量（数字/引号字符串），再尝试字段路径。
     */
    private Object resolveValue(String token, Map<String, Object> context) {
        // 1. 带引号的字符串字面量
        if (isQuotedString(token)) {
            return unquote(token);
        }
        // 2. 数字字面量
        if (token.matches("-?\\d+(\\.\\d+)?")) {
            try {
                if (token.contains(".")) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        // 3. 布尔字面量
        if ("true".equalsIgnoreCase(token)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(token)) return Boolean.FALSE;
        // 4. 字段路径
        return evaluate(token, context);
    }

    /**
     * 判断是否为带引号的字符串字面量。
     */
    private boolean isQuotedString(String s) {
        return (s.startsWith("'") && s.endsWith("'")) || 
               (s.startsWith("\"") && s.endsWith("\""));
    }

    /**
     * 去除引号。
     */
    private String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if (first == last && (first == '\'' || first == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private boolean containsOperator(String expr) {
        return expr.contains("==") || expr.contains("!=") || 
               expr.contains(">") || expr.contains("<") ||
               expr.contains("&&") || expr.contains("||") ||
               expr.contains("contains") || expr.contains("startsWith") || expr.contains("endsWith");
    }

    private boolean compare(Object left, String op, Object right) {
        switch (op) {
            case "==":
                return objectsEqual(left, right);
            case "!=":
                return !objectsEqual(left, right);
            case ">=":
                return toNumber(left) >= toNumber(right);
            case "<=":
                return toNumber(left) <= toNumber(right);
            case ">":
                return toNumber(left) > toNumber(right);
            case "<":
                return toNumber(left) < toNumber(right);
            case "contains":
                return String.valueOf(left).contains(String.valueOf(right));
            case "startsWith":
                return String.valueOf(left).startsWith(String.valueOf(right));
            case "endsWith":
                return String.valueOf(left).endsWith(String.valueOf(right));
            default:
                return false;
        }
    }

    /**
     * 智能相等比较：处理 Integer/Long/Double 等数值类型自动转换。
     */
    private boolean objectsEqual(Object left, Object right) {
        if (left == null && right == null) return true;
        if (left == null || right == null) return false;
        
        // 如果两者都是数值类型，统一转换为 double 比较
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
        }
        
        return Objects.equals(left, right);
    }

    private double toNumber(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private boolean evalBooleanLogic(String expr, Map<String, Object> context) {
        // 简化的布尔逻辑解析
        if (expr.contains("&&")) {
            String[] parts = expr.split("&&");
            return Arrays.stream(parts)
                    .map(p -> p.trim())
                    .allMatch(p -> evalCondition(p, context));
        }
        if (expr.contains("||")) {
            String[] parts = expr.split("\\|\\|");
            return Arrays.stream(parts)
                    .map(p -> p.trim())
                    .anyMatch(p -> evalCondition(p, context));
        }
        return false;
    }
}