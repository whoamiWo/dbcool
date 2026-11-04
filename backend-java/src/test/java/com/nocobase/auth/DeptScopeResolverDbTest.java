package com.nocobase.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * A-1 修复的真实 DB 验证：DEPT 部门子树解析。
 *
 * <p>用真实 SQL 建 org_unit + users，断言：
 * ①子树成员可见、兄弟部门不可见；②无部门收敛 SELF；③SQL 谓词引用真实表（无 user_u）。
 *
 * <p>与旧 {@code AclDataScopeTest} 的差别：旧测试只断言字符串里出现 department_id，
 * 表名/列名全错也照样绿灯 —— 这正是被 mock 掩盖的缺陷形态。
 */
@DataJpaTest
@ActiveProfiles("test")
class DeptScopeResolverDbTest {

    @Autowired
    private JdbcTemplate jdbc;

    private DeptScopeResolver resolver;

    private static final String TENANT = "tenant_a";

    private final UUID rootUnit = UUID.randomUUID();
    private final UUID childUnit = UUID.randomUUID();
    private final UUID otherUnit = UUID.randomUUID();
    private final UUID rootManager = UUID.randomUUID();
    private final UUID childMember = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private final UUID deptless = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resolver = new DeptScopeResolver(jdbc);
        jdbc.update("DELETE FROM org_unit");
        jdbc.update("DELETE FROM users");

        insertUnit(rootUnit, "华东大区", null, "/" + rootUnit + "/");
        insertUnit(childUnit, "华东-销售", rootUnit,
                "/" + rootUnit + "/" + childUnit + "/");
        insertUnit(otherUnit, "华南大区", null, "/" + otherUnit + "/");

        insertUser(rootManager, "root_mgr", rootUnit);
        insertUser(childMember, "sales_member", childUnit);
        insertUser(outsider, "south_member", otherUnit);
        insertUser(deptless, "no_dept", null);
    }

    private void insertUnit(UUID id, String name, UUID parent, String path) {
        jdbc.update("INSERT INTO org_unit (id, tenant_id, parent_id, name, path, sort, source, deleted, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0, 'LOCAL', FALSE, ?, ?)",
                id, TENANT, parent, name, path, Instant.now(), Instant.now());
    }

    private void insertUser(UUID id, String username, UUID dept) {
        jdbc.update("INSERT INTO users (id, username, tenant_id, enabled, password_set, created_at, status, department_id) "
                        + "VALUES (?, ?, ?, TRUE, TRUE, ?, 'ACTIVE', ?)",
                id, username, TENANT, Instant.now(), dept);
    }

    @Test
    @DisplayName("DEPT：返回整棵子树成员（含自身），不返回兄弟部门")
    void resolveVisibleCreatorIds_returnsSubtreeOnly() {
        Set<UUID> visible = resolver.resolveVisibleCreatorIds(rootManager, TENANT);

        assertThat(visible).contains(rootManager, childMember);
        assertThat(visible).doesNotContain(outsider, deptless);
    }

    @Test
    @DisplayName("无部门 → 仅本人（决策 D6）")
    void resolveVisibleCreatorIds_noDept_onlySelf() {
        Set<UUID> visible = resolver.resolveVisibleCreatorIds(deptless, TENANT);
        assertThat(visible).containsExactly(deptless);
    }

    @Test
    @DisplayName("DEPT SQL 谓词引用真实 users/org_unit 与子树 path，不再出现 user_u")
    void buildDeptScopeSql_usesRealTables() {
        String sql = resolver.buildDeptScopeSql(rootManager, TENANT, "created_by");

        assertThat(sql).isNotNull();
        assertThat(sql).contains("users u").contains("org_unit o").contains("path LIKE");
        assertThat(sql).doesNotContain("user_u");
        assertThat(sql).startsWith("created_by IN");
    }

    @Test
    @DisplayName("无部门用户 → 不生成 DEPT 谓词（调用方按 SELF 处理）")
    void buildDeptScopeSql_noDept_returnsNull() {
        assertThat(resolver.buildDeptScopeSql(deptless, TENANT, "created_by")).isNull();
    }
}
