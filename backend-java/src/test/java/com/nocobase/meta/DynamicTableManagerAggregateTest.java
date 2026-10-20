package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * PHASE105: DynamicTableManager.aggregate 测试（反射注入 mock JdbcTemplate）。
 *
 * <p>aggregate 的 SQL 是 PostgreSQL 方言（extra->>'f' / ::numeric），在 H2 上会
 * 走 catch 降级返回空列表。为覆盖「SQL 构建」本身（分组列、聚合白名单、FilterRule
 * 各 operator、WHERE/GROUP BY 拼接），这里注入 mock JdbcTemplate 捕获实际生成的 SQL，
 * 断言其结构而非执行结果。
 */
class DynamicTableManagerAggregateTest {

    private DynamicTableManager manager;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        manager = new DynamicTableManager(mock(DataSource.class));
        jdbc = mock(JdbcTemplate.class);
        ReflectionTestUtils.setField(manager, "jdbc", jdbc);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("x", 1)));
    }

    private CollectionService.FilterRule rule(String field, String op, Object value) {
        // FilterRule 是 record：构造器 (field, op, value)
        return new CollectionService.FilterRule(field, op, value);
    }

    private String captureSql() {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(cap.capture(), any(Object[].class));
        return cap.getValue();
    }

    @Test
    void aggregate_nullAggSpecs_returnsEmpty() {
        assertThat(manager.aggregate("c1", List.of("g1"), null, null)).isEmpty();
    }

    @Test
    void aggregate_emptyAggSpecs_returnsEmpty() {
        assertThat(manager.aggregate("c1", null, List.of(), null)).isEmpty();
    }

    @Test
    void aggregate_unsafeFieldSpecs_filteredOut() {
        // 字段名非法（含分号）→ 被 isSafeName 过滤 → aggs 为空 → 返回空
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("bad;drop", "SUM", "a"));

        assertThat(manager.aggregate("c1", null, specs, null)).isEmpty();
    }

    @Test
    void aggregate_singleSumNoGroup_buildsSelect() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "total"));

        manager.aggregate("c1", null, specs, null);

        String sql = captureSql();
        assertThat(sql).startsWith("SELECT ").contains("SUM(").contains("AS \"total\"");
        assertThat(sql).doesNotContain("GROUP BY"); // 无分组
    }

    @Test
    void aggregate_withGroupBy_buildsGroupByClause() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "total"));

        manager.aggregate("c1", List.of("region"), specs, null);

        String sql = captureSql();
        assertThat(sql).contains("(extra->>'region')").contains("GROUP BY");
    }

    @Test
    void aggregate_countUsesTextExpr_notNumeric() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("user", "COUNT", "cnt"));

        manager.aggregate("c1", null, specs, null);

        // COUNT 直接统计文本存在性，不走 ::numeric
        assertThat(captureSql()).contains("COUNT(extra->>'user')");
    }

    @Test
    void aggregate_nonNumericAgg_usesNumericExprGuard() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "AVG", "avg_amt"));

        manager.aggregate("c1", null, specs, null);

        // AVG 走 numericExpr：CASE WHEN ~ 正则 THEN ::numeric ELSE NULL
        assertThat(captureSql()).contains("CASE WHEN").contains("::numeric");
    }

    @Test
    void aggregate_unknownAggFunction_fallsBackToSum() {
        // 聚合函数不在白名单 → 退化为 SUM
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "DROP_TABLE", "x"));

        manager.aggregate("c1", null, specs, null);

        assertThat(captureSql()).contains("SUM(").doesNotContain("DROP_TABLE");
    }

    @Test
    void aggregate_nullAggFunction_defaultsToSum() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", null, "x"));

        manager.aggregate("c1", null, specs, null);

        assertThat(captureSql()).contains("SUM(");
    }

    @Test
    void aggregate_unsafeAlias_derivedFromFieldAndFn() {
        // alias 非法 → 用 field_fn 小写
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "bad alias!"));

        manager.aggregate("c1", null, specs, null);

        assertThat(captureSql()).contains("AS \"amount_sum\"");
    }

    @Test
    void aggregate_filterEq_buildsWhereWithBinding() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "t"));

        manager.aggregate("c1", null, specs, List.of(rule("status", "eq", "paid")));

        String sql = captureSql();
        assertThat(sql).contains("WHERE").contains("extra->>'status' = ?");
    }

    @Test
    void aggregate_filterContains_buildsIlike() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "t"));

        manager.aggregate("c1", null, specs, List.of(rule("title", "contains", "urgent")));

        assertThat(captureSql()).contains("ILIKE ?");
    }

    @Test
    void aggregate_filterGt_usesNumericComparison() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "t"));

        manager.aggregate("c1", null, specs, List.of(rule("amount", "gt", 100)));

        assertThat(captureSql()).contains("> ?").contains("::numeric");
    }

    @Test
    void aggregate_filterEmpty_buildsCoalesce() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "t"));

        manager.aggregate("c1", null, specs, List.of(rule("note", "empty", null)));

        assertThat(captureSql()).contains("COALESCE(extra->>'note','') = ''");
    }

    @Test
    void aggregate_filterUnknownOp_ignored() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "t"));

        manager.aggregate("c1", null, specs, List.of(rule("x", "bogus_op", "v")));

        // 未知 op 被忽略 → 不产生该字段的过滤条件。
        // 注意:PHASE125 起 WHERE 恒定带软删除过滤(deleted_at IS NULL),
        // 故此处不再断言"无 WHERE",改为断言被忽略的字段未进入 SQL。
        String sql = captureSql();
        assertThat(sql).doesNotContain("extra->>'x'");
        assertThat(sql).contains("deleted_at IS NULL");
    }

    @Test
    void aggregate_unsafeFilterField_ignored() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "t"));

        manager.aggregate("c1", null, specs, List.of(rule("bad;field", "eq", "v")));

        // 不安全字段名被忽略 → 不产生该字段的过滤条件(软删除过滤仍恒定存在)
        String sql = captureSql();
        assertThat(sql).doesNotContain("bad");
        assertThat(sql).contains("deleted_at IS NULL");
    }

    @Test
    void aggregate_multipleGroupFields_joinedInOrder() {
        List<DynamicTableManager.AggSpec> specs =
                List.of(new DynamicTableManager.AggSpec("amount", "SUM", "t"));

        manager.aggregate("c1", List.of("region", "year"), specs, null);

        String sql = captureSql();
        assertThat(sql).contains("extra->>'region'").contains("extra->>'year'");
        // GROUP BY 两个分组列
        assertThat(sql.substring(sql.indexOf("GROUP BY"))).contains(", ");
    }
}
