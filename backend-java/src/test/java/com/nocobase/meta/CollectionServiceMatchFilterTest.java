package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * CollectionService.matchFilter 单元测试(Week 20 P5 抬红线).
 *
 * <p>镜像前端 FilterRule op(eq/neq/contains/gt/lt/empty/notEmpty)。
 */
class CollectionServiceMatchFilterTest {

    private final CollectionService service = new CollectionService(null, null, null, null);

    /* === null handling === */

    @Test
    void nullRule_passes() {
        assertThat(service.matchFilter("x", null)).isTrue();
    }

    @Test
    void nullOp_passes() {
        assertThat(service.matchFilter("x", new CollectionService.FilterRule("f", null, "v"))).isTrue();
    }

    /* === eq === */

    @Test
    void eq_match() {
        assertThat(service.matchFilter("alice",
                new CollectionService.FilterRule("name", "eq", "alice"))).isTrue();
    }

    @Test
    void eq_mismatch() {
        assertThat(service.matchFilter("bob",
                new CollectionService.FilterRule("name", "eq", "alice"))).isFalse();
    }

    @Test
    void eq_nullValue_returnsFalse() {
        // value != null 判断 → null value 永远 false
        assertThat(service.matchFilter(null,
                new CollectionService.FilterRule("name", "eq", "alice"))).isFalse();
    }

    /* === neq === */

    @Test
    void neq_match() {
        assertThat(service.matchFilter("bob",
                new CollectionService.FilterRule("name", "neq", "alice"))).isTrue();
    }

    @Test
    void neq_sameValue_returnsFalse() {
        assertThat(service.matchFilter("alice",
                new CollectionService.FilterRule("name", "neq", "alice"))).isFalse();
    }

    /* === contains === */

    @Test
    void contains_substring() {
        assertThat(service.matchFilter("alice",
                new CollectionService.FilterRule("name", "contains", "al"))).isTrue();
    }

    @Test
    void contains_miss() {
        assertThat(service.matchFilter("alice",
                new CollectionService.FilterRule("name", "contains", "zz"))).isFalse();
    }

    @Test
    void contains_nullValue_returnsFalse() {
        assertThat(service.matchFilter(null,
                new CollectionService.FilterRule("name", "contains", "x"))).isFalse();
    }

    /* === gt / lt === */

    @Test
    void gt_numeric() {
        assertThat(service.matchFilter(100,
                new CollectionService.FilterRule("age", "gt", "50"))).isTrue();
    }

    @Test
    void gt_equal_returnsFalse() {
        assertThat(service.matchFilter(50,
                new CollectionService.FilterRule("age", "gt", "50"))).isFalse();
    }

    @Test
    void gt_stringCoerced() {
        // value 是 number,rule.value 是 string "100" → 都 toDouble → 100 vs 100 → false
        assertThat(service.matchFilter(100,
                new CollectionService.FilterRule("age", "gt", "100"))).isFalse();
    }

    @Test
    void lt_numeric() {
        assertThat(service.matchFilter(10,
                new CollectionService.FilterRule("age", "lt", "50"))).isTrue();
    }

    /* === empty / notEmpty === */

    @Test
    void empty_nullValue() {
        assertThat(service.matchFilter(null,
                new CollectionService.FilterRule("name", "empty", null))).isTrue();
    }

    @Test
    void empty_emptyString() {
        assertThat(service.matchFilter("",
                new CollectionService.FilterRule("name", "empty", null))).isTrue();
    }

    @Test
    void empty_nonEmptyValue() {
        assertThat(service.matchFilter("alice",
                new CollectionService.FilterRule("name", "empty", null))).isFalse();
    }

    @Test
    void notEmpty_hasValue() {
        assertThat(service.matchFilter("alice",
                new CollectionService.FilterRule("name", "notEmpty", null))).isTrue();
    }

    @Test
    void notEmpty_emptyValue_returnsFalse() {
        assertThat(service.matchFilter("",
                new CollectionService.FilterRule("name", "notEmpty", null))).isFalse();
    }

    /* === unknown op 视为通过 === */

    @Test
    void unknownOp_passes() {
        assertThat(service.matchFilter("x",
                new CollectionService.FilterRule("f", "between", "1"))).isTrue();
    }

    /* === toDouble helper === */

    @Test
    void toDouble_null_returnsNaN() {
        assertThat(Double.isNaN(CollectionService.toDouble(null))).isTrue();
    }

    @Test
    void toDouble_number() {
        assertThat(CollectionService.toDouble(42)).isEqualTo(42.0);
        assertThat(CollectionService.toDouble(3.14)).isEqualTo(3.14);
    }

    @Test
    void toDouble_string() {
        assertThat(CollectionService.toDouble("3.14")).isEqualTo(3.14);
    }

    @Test
    void toDouble_garbage_returnsNaN() {
        assertThat(Double.isNaN(CollectionService.toDouble("not a number"))).isTrue();
    }
}