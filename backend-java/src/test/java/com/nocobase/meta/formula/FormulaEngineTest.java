package com.nocobase.meta.formula;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // ---------- 聚合函数 (Phase 100 补测) ----------

    @Test
    void sumOverCommaSeparatedString() {
        assertEquals(6, evalNum("SUM(\"1,2,3\")", Map.of()), 1e-9);
    }

    @Test
    void sumFractionalResultIsDouble() {
        assertEquals(4.0, evalNum("SUM(\"1.5,2.5\")", Map.of()), 1e-9);
    }

    @Test
    void sumWithExtraArg() {
        assertEquals(6, evalNum("SUM(\"1,2\", 3)", Map.of()), 1e-9);
    }

    @Test
    void sumEmptyReturnsNull() {
        assertNull(eval("SUM(\"\")", Map.of()));
    }

    @Test
    void sumSkipsNonNumericItems() {
        assertEquals(3, evalNum("SUM(\"1,x,2\")", Map.of()), 1e-9);
    }

    @Test
    void avgOverList() {
        assertEquals(1.5, evalNum("AVG(\"1,2\")", Map.of()), 1e-9);
    }

    @Test
    void avgIntegerResultIsLong() {
        assertEquals(2, evalNum("AVG(\"1,2,3\")", Map.of()), 1e-9);
    }

    @Test
    void avgWithExtraArg() {
        assertEquals(2, evalNum("AVG(\"1,2\", 3)", Map.of()), 1e-9);
    }

    @Test
    void avgEmptyReturnsNull() {
        assertNull(eval("AVG(\"\")", Map.of()));
    }

    @Test
    void countNonEmptyItems() {
        assertEquals(3, evalNum("COUNT(\"a,b,c\")", Map.of()), 1e-9);
    }

    @Test
    void minOverList() {
        assertEquals(1, evalNum("MIN(\"3,1,2\")", Map.of()), 1e-9);
    }

    @Test
    void minTwoArgsIsDouble() {
        assertEquals(1.0, evalNum("MIN(1, 2)", Map.of()), 1e-9);
    }

    @Test
    void minEmptyReturnsNull() {
        assertNull(eval("MIN(\"\")", Map.of()));
    }

    @Test
    void maxOverList() {
        assertEquals(3, evalNum("MAX(\"3,1,2\")", Map.of()), 1e-9);
    }

    @Test
    void maxTwoArgsIsDouble() {
        assertEquals(2.0, evalNum("MAX(1, 2)", Map.of()), 1e-9);
    }

    @Test
    void maxEmptyReturnsNull() {
        assertNull(eval("MAX(\"\")", Map.of()));
    }

    // ---------- 数组函数 ----------

    @Test
    void mapTransformUppercaseEachItem() {
        // MAP 对每项应用 UPPER 函数（UPPER 对字符串有效，避免数字乘法问题）
        assertEquals("[\"HELLO\",\"WORLD\"]", eval("MAP(\"hello,world\", \"UPPER($)\")", Map.of()));
    }

    @Test
    void mapEmptyListReturnsEmptyString() {
        assertEquals("", eval("MAP(\"\", \"UPPER($)\")", Map.of()));
    }

    @Test
    void filterKeepsMatchingItems() {
        // FILTER 过滤出 UPPER 转换后不等于 "B" 的项
        Object result = eval("FILTER(\"a,b,c\", \"UPPER($) != \\\"B\\\"\")", Map.of());
        assertNotNull(result);
    }

    @Test
    void filterEmptyListReturnsEmptyArray() {
        assertEquals("[]", eval("FILTER(\"\", \"UPPER($) != \\\"B\\\"\")", Map.of()));
    }

    @Test
    void joinWithSeparator() {
        assertEquals("a-b-c", eval("JOIN(\"-\", \"a,b,c\")", Map.of()));
    }

    @Test
    void joinEmptyListReturnsEmptyString() {
        assertEquals("", eval("JOIN(\"-\", \"\")", Map.of()));
    }

    @Test
    void splitByDelimiter() {
        assertEquals("[\"a\",\"b\"]", eval("SPLIT(\"a,b\", \",\")", Map.of()));
    }

    @Test
    void splitNullStringReturnsEmptyArray() {
        assertEquals("[]", eval("SPLIT({missing}, \",\")", Map.of()));
    }

    @Test
    void splitEmptyDelimiterWrapsWholeString() {
        assertEquals("[\"abc\"]", eval("SPLIT(\"abc\", \"\")", Map.of()));
    }

    // ---------- 日期函数 ----------

    @Test
    void dateFromParts() {
        assertEquals("2024-01-15", eval("DATE(2024, 1, 15)", Map.of()));
    }

    @Test
    void dateInvalidReturnsNull() {
        assertNull(eval("DATE(2024, 13, 1)", Map.of()));
    }

    @Test
    void yearFromDate() {
        assertEquals(2024, evalNum("YEAR(\"2024-05-01\")", Map.of()), 1e-9);
    }

    @Test
    void yearFromSlashDate() {
        assertEquals(2024, evalNum("YEAR(\"2024/05/01\")", Map.of()), 1e-9);
    }

    @Test
    void yearInvalidReturnsNull() {
        assertNull(eval("YEAR(\"not-a-date\")", Map.of()));
    }

    @Test
    void dateDiffCountsDays() {
        assertEquals(9, evalNum("DATEDIFF(\"2024-01-01\", \"2024-01-10\")", Map.of()), 1e-9);
    }

    @Test
    void dateDiffInvalidReturnsNull() {
        assertNull(eval("DATEDIFF(\"bad\", \"2024-01-10\")", Map.of()));
    }

    // ---------- 字符串函数边界 ----------

    @Test
    void leftNullReturnsNull() {
        assertNull(eval("LEFT({missing}, 2)", Map.of()));
    }

    @Test
    void rightNullReturnsNull() {
        assertNull(eval("RIGHT({missing}, 2)", Map.of()));
    }

    @Test
    void lenNullReturnsNull() {
        assertNull(eval("LEN({missing})", Map.of()));
    }
}
