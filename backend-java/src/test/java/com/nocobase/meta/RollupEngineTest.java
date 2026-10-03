package com.nocobase.meta;

import com.nocobase.meta.rollup.RollupEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PHASE71：rollup / lookup 聚合引擎回归防线。
 *
 * <p>背景（审计发现的真实缺陷）：{@code CollectionService#resolveRelatedRecords}
 * 此前把关联记录裁剪成 {@code {id, title}} 两项后交给本引擎聚合，导致
 * SUM / AVG / MIN / MAX 恒为 0、lookup 取非 title 字段恒为 null —— 只有 COUNT 能用。
 * 该缺陷长期未被发现，是因为前端此前无法创建 rollup / lookup 字段。
 *
 * <p>本测试锁死两件事：
 * <ol>
 *   <li>给定**完整**关联记录时，各聚合函数结果正确（正向）；</li>
 *   <li>若上游又退化成只给 {@code {id, title}}，SUM 必然为 0（反向验证 ——
 *       证明"必须加载完整字段"这条约束是有可观测后果的，不是纸面要求）。</li>
 * </ol>
 */
class RollupEngineTest {

    /** 完整关联记录（PHASE71 修复后 resolveRelatedRecords 的返回形态）。 */
    private static List<Map<String, Object>> fullRecords() {
        return List.of(
                Map.of("id", "1", "title", "a", "amount", 10),
                Map.of("id", "2", "title", "b", "amount", 20),
                Map.of("id", "3", "title", "c", "amount", 30)
        );
    }

    /** 被裁剪的关联记录（修复前的错误形态，仅 id/title）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> trimmedRecords() {
        return List.of(
                Map.of("id", "1", "title", "a"),
                Map.of("id", "2", "title", "b"),
                Map.of("id", "3", "title", "c")
        );
    }

    private static double value(List<Map<String, Object>> agg) {
        return ((Number) agg.get(0).get("value")).doubleValue();
    }

    @Test
    void sum_withFullRecords_returnsTotal() {
        assertEquals(60.0, value(RollupEngine.aggregate(fullRecords(), "SUM", "amount")), 0.001);
    }

    @Test
    void avg_min_max_withFullRecords_areCorrect() {
        assertEquals(20.0, value(RollupEngine.aggregate(fullRecords(), "AVG", "amount")), 0.001);
        assertEquals(10.0, value(RollupEngine.aggregate(fullRecords(), "MIN", "amount")), 0.001);
        assertEquals(30.0, value(RollupEngine.aggregate(fullRecords(), "MAX", "amount")), 0.001);
    }

    @Test
    void count_doesNotDependOnFieldValues() {
        assertEquals(3.0, value(RollupEngine.aggregate(fullRecords(), "COUNT", "amount")), 0.001);
        // COUNT 不读字段，因此裁剪形态下也能工作 —— 这正是缺陷只影响 SUM/AVG/MIN/MAX 的原因
        assertEquals(3.0, value(RollupEngine.aggregate(trimmedRecords(), "COUNT", "amount")), 0.001);
    }

    @Test
    void sum_withTrimmedRecords_isZero_reverseProof() {
        // 反向验证：上游若退回只给 id/title，SUM 必为 0 —— 说明"加载完整字段"是硬要求
        assertEquals(0.0, value(RollupEngine.aggregate(trimmedRecords(), "SUM", "amount")), 0.001);
    }

    @Test
    void avg_withTrimmedRecords_isUnusable_reverseProof() {
        // AVG 在拿不到任何数值时更糟：平均值无从计算，Map.of("value", null) 直接抛 NPE
        // （生产上被 CollectionService#applyRollupAndLookup 的 catch 兜住 → 字段不set）。
        // 无论抛异常还是给 0，结论一致：裁剪形态下 rollup 不可用。
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> RollupEngine.aggregate(trimmedRecords(), "AVG", "amount"));
    }

    @Test
    void lookup_withFullRecords_returnsFieldValue() {
        Object v = RollupEngine.lookup(fullRecords(), "amount");
        assertTrue(v instanceof Number, "lookup 应取到关联记录的 amount 值");
    }

    @Test
    void lookup_withTrimmedRecords_returnsNull_reverseProof() {
        assertEquals(null, RollupEngine.lookup(trimmedRecords(), "amount"),
                "关联记录被裁剪后 lookup 取不到 amount —— 证明必须加载完整字段");
    }

    @Test
    void aggregate_withEmptyRecords_returnsEmpty() {
        assertTrue(RollupEngine.aggregate(List.of(), "SUM", "amount").isEmpty());
    }
}
