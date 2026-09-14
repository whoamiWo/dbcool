package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * DynamicTableManager.buildOrderBy 单元测试(Week 18 P5 阶段 5).
 *
 * <p>直接调 package-private 方法(JaCoCo 计入覆盖率);覆盖基本白名单、SQL injection 防御、空/null。
 */
class DynamicTableManagerOrderByTest {

    private final DynamicTableManager mgr = new DynamicTableManager(mock(DataSource.class));

    private String buildOrderBy(String sortExpr, List<FieldDef> fields) {
        return mgr.buildOrderBy(sortExpr, fields);
    }

    private static FieldDef field(String name) {
        return new FieldDef(name, "text", false, null, null);
    }

    private static List<FieldDef> fields(String... names) {
        List<FieldDef> out = new ArrayList<>();
        for (String n : names) out.add(field(n));
        return out;
    }

    /* === basic === */

    @Test void singleField_asc() {
        String sql = buildOrderBy("name", fields("name", "salary"));
        assertThat(sql).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void singleField_desc() {
        String sql = buildOrderBy("-salary", fields("name", "salary"));
        assertThat(sql).isEqualTo("(extra->>'salary') DESC NULLS LAST");
    }

    @Test void singleField_plusPrefix() {
        String sql = buildOrderBy("+name", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void systemField_created_at() {
        String sql = buildOrderBy("created_at", fields("name"));
        assertThat(sql).isEqualTo("created_at ASC NULLS LAST");
    }

    @Test void systemField_updated_at_desc() {
        String sql = buildOrderBy("-updated_at", fields("name"));
        assertThat(sql).isEqualTo("updated_at DESC NULLS LAST");
    }

    @Test void multipleFields() {
        String sql = buildOrderBy("name,-salary", fields("name", "salary", "age"));
        assertThat(sql).isEqualTo(
                "(extra->>'name') ASC NULLS LAST, (extra->>'salary') DESC NULLS LAST");
    }

    @Test void multipleFields_mixedSystem() {
        String sql = buildOrderBy("name,-created_at", fields("name", "salary"));
        assertThat(sql).isEqualTo(
                "(extra->>'name') ASC NULLS LAST, created_at DESC NULLS LAST");
    }

    @Test void trimWhitespace() {
        String sql = buildOrderBy("  name  ,  -salary  ", fields("name", "salary"));
        assertThat(sql).isEqualTo(
                "(extra->>'name') ASC NULLS LAST, (extra->>'salary') DESC NULLS LAST");
    }

    /* === SQL injection === */

    @Test void sqlInjection_dropTable() {
        String sql = buildOrderBy("name; DROP TABLE data_customer--", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_semicolon() {
        String sql = buildOrderBy("name;DROP", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_space() {
        String sql = buildOrderBy("na me", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_quote() {
        String sql = buildOrderBy("name' OR 1=1--", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_startsWithDigit() {
        String sql = buildOrderBy("1name", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_underscoreOk() {
        String sql = buildOrderBy("_internal", fields("_internal"));
        assertThat(sql).isEqualTo("(extra->>'_internal') ASC NULLS LAST");
    }

    @Test void sqlInjection_pgKeyword() {
        // 'order' 是 PG 关键字,但 buildOrderBy 只挡"语法注入"
        // 真正的 SQL 是 "ORDER BY (extra->>'order') ASC NULLS LAST"
        // 字段名 'order' 用 jsonb 操作符,不是裸 token — 安全
        String sql = buildOrderBy("order", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'order') ASC NULLS LAST");
    }

    @Test void sqlInjection_hyphen() {
        String sql = buildOrderBy("name-extra", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_mixedTokens() {
        String sql = buildOrderBy("name,name;DROP", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    /* === 空/null === */

    @Test void null_sortExpr() {
        assertThat(buildOrderBy(null, fields("name"))).isNull();
    }

    @Test void empty_sortExpr() {
        assertThat(buildOrderBy("", fields("name"))).isNull();
    }

    @Test void blank_sortExpr() {
        assertThat(buildOrderBy("   ", fields("name"))).isNull();
    }

    @Test void null_fields() {
        assertThat(buildOrderBy("name", null)).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void empty_fields() {
        assertThat(buildOrderBy("name", List.of())).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void allTokensInvalid() {
        // DROP 是合法 token(语法上),na me 含空格被拒,1abc 数字开头被拒
        // 所以仅 DROP 通过 — 期望得到 1 个 ORDER BY clause
        String sql = buildOrderBy("DROP,na me,1abc", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'DROP') ASC NULLS LAST");
    }

    @Test void allTokensTrulyInvalid() {
        // 全部 token 都语法非法 → null
        assertThat(buildOrderBy("na me,1abc", fields("name"))).isNull();
    }

    @Test void fieldNotInSchemaButValid() {
        String sql = buildOrderBy("age", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'age') ASC NULLS LAST");
    }
}