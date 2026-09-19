package com.nocobase.meta.formula;

import com.googlecode.aviator.AviatorEvaluator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formula 字段求值引擎(Week 46 Airtable 引擎 / Phase 48 接真)。
 *
 * <p>在 {@link com.nocobase.common.ExpressionEvaluator}(工作流条件裸表达式通道)之上,
 * 额外支持 Airtable 公式方言,专供 Collection 的 {@code formula} 派生字段:
 * <ul>
 *   <li>字段引用 {@code {price} * {qty}} — 花括号引用当前行字段,预处理为合法 Aviator 变量</li>
 *   <li>算术 {@code + - * / %};比较 {@code == != > < >= <=};逻辑 {@code && || !}</li>
 *   <li>函数 IF / AND / OR / CONCAT / LEFT / RIGHT / LEN / UPPER / LOWER / TRIM / ROUND / ABS</li>
 * </ul>
 *
 * <p><strong>安全约束</strong>(与 ExpressionEvaluator 一致):表达式长度上限 1000 字符;
 * 不开启 Java 反射调用;求值异常(空表达式 / 字段缺失 / 除零 / 类型不匹配)一律降级为
 * {@code null},绝不向外抛、不阻断整条记录返回。
 */
public final class FormulaEngine {

    /** 表达式最大长度(防超长表达式 DoS)。 */
    private static final int MAX_EXPRESSION_LENGTH = 1000;

    /** Airtable 风格字段引用 {@code {field}}。 */
    private static final Pattern FIELD_REF = Pattern.compile("\\{([^{}]+)}");

    static {
        FormulaFunctions.registerAll();
    }

    private FormulaEngine() {
    }

    /**
     * 求值公式;失败返回 {@code null},不抛异常。
     *
     * @param expression 公式表达式,支持 {@code {field}} 引用当前行字段
     * @param row        当前行数据(变量环境),可为 {@code null}
     * @return 求值结果;表达式为空 / 非法 / 求值异常时返回 {@code null}
     */
    public static Object evaluate(String expression, Map<String, Object> row) {
        if (expression == null || expression.isBlank()) return null;
        if (expression.length() > MAX_EXPRESSION_LENGTH) return null;
        try {
            Map<String, Object> env = new HashMap<>();
            if (row != null) env.putAll(row);
            String processed = substituteFieldRefs(expression, env);
            return AviatorEvaluator.compile(processed, true).execute(env);
        } catch (Exception e) {
            // 语法错误 / 除零 / 字段缺失导致 NPE / 类型不匹配 → 降级,不阻断整条记录
            return null;
        }
    }

    /**
     * 把 {@code {field}} 引用替换为合成变量 {@code __f{i}__}(合法 Aviator 标识符),
     * 字段名可含任意字符(如 {@code field-1});缺失字段的值为 {@code null}(Aviator nil)。
     */
    private static String substituteFieldRefs(String expression, Map<String, Object> env) {
        Matcher m = FIELD_REF.matcher(expression);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (m.find()) {
            String field = m.group(1).trim();
            String var = "__f" + (idx++) + "__";
            env.put(var, env.get(field));
            m.appendReplacement(sb, var);
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
