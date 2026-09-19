package com.nocobase.meta.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FormulaEngine 接真验证(Phase 48 F1)。
 *
 * <p>覆盖:算术 / 比较 / 逻辑 / 函数(IF AND OR CONCAT LEFT RIGHT LEN UPPER LOWER TRIM
 * ROUND ABS)/ {field} 字段引用 / 异常边界(空 / 超长 / 缺字段 / 除零 / 语法错)。
 */
class FormulaEngineTest {

    private static Object eval(String expr, Map<String, Object> row) {
        return FormulaEngine.evaluate(expr, row);
    }

    private static double evalNum(String expr, Map<String, Object> row) {
        return ((Number) eval(expr, row)).doubleValue();
    }

    // ---------- 边界 ----------

    @Test
    void nullExpressionReturnsNull() {
        assertNull(eval(null, Map.of("a", 1)));
    }

    @Test
    void blankExpressionReturnsNull() {
        assertNull(eval("   ", Map.of("a", 1)));
    }

    @Test
    void nullRowBareExpressionReturnsNull() {
        // row 为 null 时,含字段引用的表达式因变量缺失降级为 null
        assertNull(eval("{a} + 1", null));
    }

    @Test
    void overlongExpressionReturnsNull() {
        assertNull(eval("1+" .repeat(600), Map.of()));
    }

    @Test
    void divideByZeroReturnsNull() {
        assertNull(eval("1 / 0", Map.of()));
    }

    @Test
    void syntaxErrorReturnsNull() {
        assertNull(eval("price *", Map.of("price", 2)));
    }

    @Test
    void missingFieldReturnsNull() {
        assertNull(eval("{nope} + 1", Map.of("a", 1)));
    }

    @Test
    void missingFieldNilArithmeticReturnsNull() {
        assertNull(eval("{price} * {qty}", Map.of("price", 2)));
    }

    // ---------- 算术 ----------

    @Test
    void arithmeticPrecedence() {
        assertEquals(14, evalNum("2 + 3 * 4", Map.of()), 1e-9);
    }

    @Test
    void subtraction() {
        assertEquals(1, evalNum("10 - 9", Map.of()), 1e-9);
    }

    @Test
    void division() {
        // Aviator 整数除法截断;浮点除法保留小数
        assertEquals(2, evalNum("10 / 4", Map.of()), 1e-9);
        assertEquals(2.5, evalNum("10 / 4.0", Map.of()), 1e-9);
    }

    @Test
    void modulo() {
        assertEquals(1, evalNum("10 % 3", Map.of()), 1e-9);
    }

    @Test
    void stringConcatOperator() {
        assertEquals("ab", eval("\"a\" + \"b\"", Map.of()));
    }

    // ---------- 比较 / 逻辑 ----------

    @Test
    void greaterThan() {
        assertTrue((Boolean) eval("3 > 2", Map.of()));
    }

    @Test
    void lessThanOrEqual() {
        assertTrue((Boolean) eval("2 <= 2", Map.of()));
    }

    @Test
    void equality() {
        assertTrue((Boolean) eval("1 + 1 == 2", Map.of()));
    }

    @Test
    void inequality() {
        assertTrue((Boolean) eval("1 != 2", Map.of()));
    }

    @Test
    void logicAnd() {
        assertTrue((Boolean) eval("1 > 0 && 2 > 1", Map.of()));
    }

    @Test
    void logicOr() {
        assertTrue((Boolean) eval("1 > 2 || 2 > 1", Map.of()));
    }

    @Test
    void logicNot() {
        assertTrue((Boolean) eval("!(3 > 4)", Map.of()));
    }

    // ---------- 字段引用 ----------

    @Test
    void fieldRefArithmetic() {
        assertEquals(200, evalNum("{price} * {qty}", Map.of("price", 20, "qty", 10)), 1e-9);
    }

    @Test
    void fieldRefBareName() {
        assertEquals(30, evalNum("price * qty", Map.of("price", 3, "qty", 10)), 1e-9);
    }

    @Test
    void fieldRefSpecialCharsInName() {
        assertEquals(6, evalNum("{field-1} * 2", Map.of("field-1", 3)), 1e-9);
    }

    @Test
    void fieldRefNestedInFunction() {
        assertEquals("X20", eval("UPPER(CONCAT(\"x\", {price}))", Map.of("price", 20)));
    }

    // ---------- 函数 ----------

    @Test
    void ifTrueBranch() {
        assertEquals(1, evalNum("IF(1 > 0, 1, 2)", Map.of()), 1e-9);
    }

    @Test
    void ifFalseBranch() {
        assertEquals(2, evalNum("IF(1 > 2, 1, 2)", Map.of()), 1e-9);
    }

    @Test
    void andFunctionTrue() {
        assertTrue((Boolean) eval("AND(1 > 0, 2 > 1)", Map.of()));
    }

    @Test
    void andFunctionFalse() {
        assertFalse((Boolean) eval("AND(1 > 0, 2 < 1)", Map.of()));
    }

    @Test
    void orFunctionTrue() {
        assertTrue((Boolean) eval("OR(1 > 2, 2 > 1)", Map.of()));
    }

    @Test
    void concatTwoArgs() {
        assertEquals("hello world", eval("CONCAT(\"hello\", \" world\")", Map.of()));
    }

    @Test
    void concatThreeArgs() {
        assertEquals("abc", eval("CONCAT(\"a\", \"b\", \"c\")", Map.of()));
    }

    @Test
    void leftFunction() {
        assertEquals("he", eval("LEFT(\"hello\", 2)", Map.of()));
    }

    @Test
    void rightFunction() {
        assertEquals("lo", eval("RIGHT(\"hello\", 2)", Map.of()));
    }

    @Test
    void lenFunction() {
        assertEquals(5, evalNum("LEN(\"hello\")", Map.of()), 1e-9);
    }

    @Test
    void upperLowerFunctions() {
        assertEquals("AB", eval("UPPER(LOWER(\"Ab\"))", Map.of()));
    }

    @Test
    void trimFunction() {
        assertEquals("ab", eval("TRIM(\" ab \")", Map.of()));
    }

    @Test
    void roundFunction() {
        assertEquals(3, evalNum("ROUND(2.5)", Map.of()), 1e-9);
    }

    @Test
    void roundWithDigits() {
        assertEquals(2.57, evalNum("ROUND(2.567, 2)", Map.of()), 1e-9);
    }

    @Test
    void absFunction() {
        assertEquals(3, evalNum("ABS(-3)", Map.of()), 1e-9);
    }

    @Test
    void nestedIfAndConcat() {
        assertEquals("high", eval("IF({v} > 10, CONCAT(\"hi\", \"gh\"), \"low\")", Map.of("v", 20)));
    }
}
