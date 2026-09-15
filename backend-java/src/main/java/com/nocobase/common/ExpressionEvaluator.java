package com.nocobase.common;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 表达式求值服务(Week 41 复核 D4b.4 / D1.3)。
 *
 * <p>基于 Aviator,供两处共用,保证语法一致:
 * <ol>
 *   <li>工作流条件节点(替换此前硬编码的 eq/neq/gt/lt/contains 五个比较符)</li>
 *   <li>Collection 的 {@code formula} 字段求值(此前仅有类型名、无任何计算)</li>
 * </ol>
 *
 * <p><strong>安全约束</strong>(对应风险 C-R05 沙箱逃逸):
 * <ul>
 *   <li>表达式长度上限 {@value #MAX_EXPRESSION_LENGTH} 字符,防 DoS</li>
 *   <li>不注册任何自定义函数,不开启 Java 方法反射调用</li>
 *   <li>求值异常一律降级为 {@code null} / {@code false},绝不向外抛</li>
 * </ul>
 */
@Component
public class ExpressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ExpressionEvaluator.class);

    /** 表达式最大长度(防超长表达式 DoS)。 */
    private static final int MAX_EXPRESSION_LENGTH = 1000;

    /**
     * 求值表达式,返回原始结果。
     *
     * @return 求值结果;表达式非法 / 超长 / 求值异常时返回 {@code null}
     */
    public Object evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) return null;
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            log.warn("[expr] 表达式超长({} > {}),拒绝求值",
                    expression.length(), MAX_EXPRESSION_LENGTH);
            return null;
        }
        try {
            Expression compiled = AviatorEvaluator.compile(expression);
            return compiled.execute(context == null ? Map.of() : context);
        } catch (Exception e) {
            // 表达式语法错误 / 运行时异常 → 降级,不阻断主流程
            log.warn("[expr] 求值失败(expression={}): {}", expression, e.getMessage());
            return null;
        }
    }

    /**
     * 求值并转为布尔值。
     *
     * @return 布尔结果;非布尔结果按 {@code Boolean.parseBoolean} 转换;
     *         求值失败或结果为 null 时返回 {@code false}(fail-safe)
     */
    public boolean evaluateBoolean(String expression, Map<String, Object> context) {
        Object r = evaluate(expression, context);
        if (r == null) return false;
        if (r instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(r));
    }
}
