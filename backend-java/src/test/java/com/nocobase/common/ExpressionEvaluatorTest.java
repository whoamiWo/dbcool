package com.nocobase.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** PHASE105: 表达式求值器单元测试。 */
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    @Test
    void evaluate_nullOrBlank_returnsNull() {
        assertThat(evaluator.evaluate(null, Map.of())).isNull();
        assertThat(evaluator.evaluate("", Map.of())).isNull();
        assertThat(evaluator.evaluate("   ", Map.of())).isNull();
    }

    @Test
    void evaluate_tooLong_returnsNull() {
        String longExpr = "x".repeat(1001);
        assertThat(evaluator.evaluate(longExpr, Map.of())).isNull();
    }

    @Test
    void evaluate_validArithmetic_returnsResult() {
        Object r = evaluator.evaluate("1 + 2 * 3", null);
        assertThat(r).isEqualTo(7L); // Aviator 整数运算
    }

    @Test
    void evaluate_withContext_returnsResult() {
        Object r = evaluator.evaluate("a + b", Map.of("a", 10, "b", 5));
        assertThat(r).isEqualTo(15L);
    }

    @Test
    void evaluate_invalidSyntax_returnsNull() {
        assertThat(evaluator.evaluate("1 + + 2", Map.of())).isNull();
        assertThat(evaluator.evaluate("foo(", Map.of())).isNull();
    }

    @Test
    void evaluate_compareOperators_returnBoolean() {
        assertThat(evaluator.evaluate("5 > 3", Map.of())).isEqualTo(Boolean.TRUE);
        assertThat(evaluator.evaluate("5 == 3", Map.of())).isEqualTo(Boolean.FALSE);
        assertThat(evaluator.evaluate("5 != 3", Map.of())).isEqualTo(Boolean.TRUE);
    }

    @Test
    void evaluate_exception_returnsNull_notThrow() {
        // 除零等运行时异常也应降级为 null
        assertThat(evaluator.evaluate("1/0", Map.of())).isNull();
    }

    @Test
    void evaluateBoolean_nullOrFalse_returnsFalse() {
        assertThat(evaluator.evaluateBoolean(null, Map.of())).isFalse();
        assertThat(evaluator.evaluateBoolean("", Map.of())).isFalse();
        assertThat(evaluator.evaluateBoolean("false", Map.of())).isFalse();
    }

    @Test
    void evaluateBoolean_trueVariants_returnTrue() {
        assertThat(evaluator.evaluateBoolean("true", Map.of())).isTrue();
        assertThat(evaluator.evaluateBoolean("1 > 0", Map.of())).isTrue();
        // Java parseBoolean 区分大小写：大写 "TRUE" 解析为 false
        assertThat(evaluator.evaluateBoolean("TRUE", Map.of())).isFalse();
    }

    @Test
    void evaluateBoolean_nonBoolean_convertedByParseBoolean() {
        assertThat(evaluator.evaluateBoolean("yes", Map.of())).isFalse(); // parseBoolean("yes")=false
        assertThat(evaluator.evaluateBoolean("on", Map.of())).isFalse();
        assertThat(evaluator.evaluateBoolean("true", Map.of())).isTrue();
    }

    @Test
    void evaluateBoolean_invalidExpr_returnsFalse() {
        assertThat(evaluator.evaluateBoolean("syntax!!!", Map.of())).isFalse();
    }
}
