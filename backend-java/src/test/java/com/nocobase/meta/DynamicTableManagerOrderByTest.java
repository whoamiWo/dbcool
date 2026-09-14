package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * DynamicTableManager.buildOrderBy 单元测试(Week 18 P5 阶段 5).
 *
 * <p>通过反射调 private 静态方法;覆盖基本白名单、SQL injection 防御、空/null。
 */
class DynamicTableManagerOrderByTest {

    private final DynamicTableManager mgr = new DynamicTableManager(mock(DataSource.class));

    private String buildOrderBy(String sortExpr, List<FieldDef> fields) throws Exception {
        Method method = DynamicTableManager.class.getDeclaredMethod(
                "buildOrderBy", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(mgr, sortExpr, fields);
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

    @Test void singleField_asc() throws Exception {
        String sql = buildOrderBy("name", fields("name", "salary"));
        assertThat(sql).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void singleField_desc() throws Exception {
        String sql = buildOrderBy("-salary", fields("name", "salary"));
        assertThat(sql).isEqualTo("(extra->>'salary') DESC NULLS LAST");
    }

    @Test void singleField_plusPrefix() throws Exception {
        String sql = buildOrderBy("+name", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void systemField_created_at() throws Exception {
        String sql = buildOrderBy("created_at", fields("name"));
        assertThat(sql).isEqualTo("created_at ASC NULLS LAST");
    }

    @Test void systemField_updated_at_desc() throws Exception {
        String sql = buildOrderBy("-updated_at", fields("name"));
        assertThat(sql).isEqualTo("updated_at DESC NULLS LAST");
    }

    @Test void multipleFields() throws Exception {
        String sql = buildOrderBy("name,-salary", fields("name", "salary", "age"));
        assertThat(sql).isEqualTo(
                "(extra->>'name') ASC NULLS LAST, (extra->>'salary') DESC NULLS LAST");
    }

    @Test void multipleFields_mixedSystem() throws Exception {
        String sql = buildOrderBy("name,-created_at", fields("name", "salary"));
        assertThat(sql).isEqualTo(
                "(extra->>'name') ASC NULLS LAST, created_at DESC NULLS LAST");
    }

    @Test void trimWhitespace() throws Exception {
        String sql = buildOrderBy("  name  ,  -salary  ", fields("name", "salary"));
        assertThat(sql).isEqualTo(
                "(extra->>'name') ASC NULLS LAST, (extra->>'salary') DESC NULLS LAST");
    }

    /* === SQL injection === */

    @Test void sqlInjection_dropTable() throws Exception {
        String sql = buildOrderBy("name; DROP TABLE data_customer--", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_semicolon() throws Exception {
        String sql = buildOrderBy("name;DROP", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_space() throws Exception {
        String sql = buildOrderBy("na me", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_quote() throws Exception {
        String sql = buildOrderBy("name' OR 1=1--", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_startsWithDigit() throws Exception {
        String sql = buildOrderBy("1name", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_underscoreOk() throws Exception {
        String sql = buildOrderBy("_internal", fields("_internal"));
        assertThat(sql).isEqualTo("(extra->>'_internal') ASC NULLS LAST");
    }

    @Test void sqlInjection_pgKeyword() throws Exception {
        // 'order' 是 PG 关键字,但 buildOrderBy 只挡"语法注入"
        // 真正的 SQL 是 "ORDER BY (extra->>'order') ASC NULLS LAST"
        // 字段名 'order' 用 jsonb 操作符,不是裸 token — 安全
        String sql = buildOrderBy("order", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'order') ASC NULLS LAST");
    }

    @Test void sqlInjection_hyphen() throws Exception {
        String sql = buildOrderBy("name-extra", fields("name"));
        assertThat(sql).isNull();
    }

    @Test void sqlInjection_mixedTokens() throws Exception {
        String sql = buildOrderBy("name,name;DROP", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    /* === 空/null === */

    @Test void null_sortExpr() throws Exception {
        assertThat(buildOrderBy(null, fields("name"))).isNull();
    }

    @Test void empty_sortExpr() throws Exception {
        assertThat(buildOrderBy("", fields("name"))).isNull();
    }

    @Test void blank_sortExpr() throws Exception {
        assertThat(buildOrderBy("   ", fields("name"))).isNull();
    }

    @Test void null_fields() throws Exception {
        assertThat(buildOrderBy("name", null)).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void empty_fields() throws Exception {
        assertThat(buildOrderBy("name", List.of())).isEqualTo("(extra->>'name') ASC NULLS LAST");
    }

    @Test void allTokensInvalid() throws Exception {
        // DROP 是合法 token(语法上),na me 含空格被拒,1abc 数字开头被拒
        // 所以仅 DROP 通过 — 期望得到 1 个 ORDER BY clause
        String sql = buildOrderBy("DROP,na me,1abc", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'DROP') ASC NULLS LAST");
    }

    @Test void allTokensTrulyInvalid() throws Exception {
        // 全部 token 都语法非法 → null
        assertThat(buildOrderBy("na me,1abc", fields("name"))).isNull();
    }

    @Test void fieldNotInSchemaButValid() throws Exception {
        String sql = buildOrderBy("age", fields("name"));
        assertThat(sql).isEqualTo("(extra->>'age') ASC NULLS LAST");
    }
}