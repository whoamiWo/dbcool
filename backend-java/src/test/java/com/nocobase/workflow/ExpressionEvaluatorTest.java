package com.nocobase.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExpressionEvaluator 纯单元测试（无 Spring 上下文）。
 */
class ExpressionEvaluatorTest {

    private ExpressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ExpressionEvaluator(new ObjectMapper());
    }

    @Test
    void testSimpleFieldAccess() {
        Object result = evaluator.evaluate("amount", Map.of("amount", 100));
        assertEquals(100, result); // 直接比较值，不强制转为 String
    }

    @Test
    void testNestedFieldAccess() {
        Object result = evaluator.evaluate("user.name", Map.of("user", Map.of("name", "Alice")));
        assertEquals("Alice", result);
    }

    @Test
    void testVariableSubstitution() {
        Object result = evaluator.evaluate("Hello {{user.name}}", Map.of("user", Map.of("name", "Bob")));
        assertEquals("Hello Bob", result);
    }

    @Test
    void testEqualityComparison() {
        Boolean result = (Boolean) evaluator.evaluate("age == 25", Map.of("age", 25));
        assertTrue(result);
    }

    @Test
    void testNotEqualsComparison() {
        Boolean result = (Boolean) evaluator.evaluate("status != 'inactive'", Map.of("status", "active"));
        assertTrue(result);
    }

    @Test
    void testGreaterThan() {
        Boolean result = (Boolean) evaluator.evaluate("amount > 100", Map.of("amount", 150));
        assertTrue(result);
    }

    @Test
    void testLessThan() {
        Boolean result = (Boolean) evaluator.evaluate("count < 10", Map.of("count", 5));
        assertTrue(result);
    }

    @Test
    void testGreaterThanOrEqual() {
        Boolean result = (Boolean) evaluator.evaluate("score >= 60", Map.of("score", 75));
        assertTrue(result);
    }

    @Test
    void testLessThanOrEqual() {
        Boolean result = (Boolean) evaluator.evaluate("score <= 100", Map.of("score", 85));
        assertTrue(result);
    }

    @Test
    void testContainsOperator() {
        Boolean result = (Boolean) evaluator.evaluate("tags contains 'important'", Map.of("tags", "important,urgent"));
        assertTrue(result);
    }

    @Test
    void testStartsWithOperator() {
        Boolean result = (Boolean) evaluator.evaluate("name startsWith 'Al'", Map.of("name", "Alice"));
        assertTrue(result);
    }

    @Test
    void testEndsWithOperator() {
        Boolean result = (Boolean) evaluator.evaluate("email endsWith '@gmail.com'", Map.of("email", "test@gmail.com"));
        assertTrue(result);
    }

    @Test
    void testAndOperator() {
        Boolean result = (Boolean) evaluator.evaluate("age > 18 && status == 'active'", Map.of("age", 25, "status", "active"));
        assertTrue(result);
    }

    @Test
    void testOrOperator() {
        Boolean result = (Boolean) evaluator.evaluate("role == 'admin' || role == 'superuser'", Map.of("role", "admin"));
        assertTrue(result);
    }

    @Test
    void testNullExpression() {
        Object result = evaluator.evaluate(null, Map.of());
        assertNull(result);
    }

    @Test
    void testEmptyExpression() {
        Object result = evaluator.evaluate("", Map.of());
        assertNull(result);
    }

    @Test
    void testArrayIndexAccess() {
        Object result = evaluator.evaluate("items[0]", Map.of("items", new String[]{"first", "second"}));
        assertEquals("first", result);
    }
}
