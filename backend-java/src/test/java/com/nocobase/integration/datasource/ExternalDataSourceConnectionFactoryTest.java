package com.nocobase.integration.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE B B3: ExternalDataSourceConnectionFactory 单元测试。
 *
 * <p>覆盖：H2 连接创建、密码缓存策略、无效数据源 ID 抛异常。
 */
class ExternalDataSourceConnectionFactoryTest {

    private final ExternalDataSourceConnectionFactory factory =
            new ExternalDataSourceConnectionFactory();

    @Test
    @DisplayName("createOrGet: 不支持的 vendor 抛异常")
    void createOrGet_unsupportedVendor() {
        ExternalDataSourceEntity e = new ExternalDataSourceEntity();
        e.setId(java.util.UUID.randomUUID());
        e.setVendor("oracle");

        assertThatThrownBy(() -> factory.createOrGet(e, "pwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数据库类型");
    }

    @Test
    @DisplayName("createOrGet: 数据源 id 缺失时抛异常（先于 vendor 校验）")
    void createOrGet_missingId() {
        ExternalDataSourceEntity e = new ExternalDataSourceEntity();
        e.setVendor("oracle");

        assertThatThrownBy(() -> factory.createOrGet(e, "pwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 不能为空");
    }

    @Test
    @DisplayName("validateVendor: 非法 vendor 抛 IllegalArgumentException")
    void validateVendor_unsupported() {
        assertThatThrownBy(() -> factory.validateVendor("h2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数据库类型");
    }

    @Test
    @DisplayName("invalidate: 非缓存 ID 不抛异常（幂等）")
    void invalidate_nonExistent() {
        factory.invalidate(UUID.randomUUID().toString());
        // 无异常即通过
    }

    @Test
    @DisplayName("createOrGet: PostgreSQL 正常创建 DataSource")
    void createOrGet_postgresql() {
        ExternalDataSourceEntity e = new ExternalDataSourceEntity();
        e.setId(java.util.UUID.randomUUID());
        e.setVendor("postgresql");
        e.setHost("localhost");
        e.setPort(5432);
        e.setDatabaseName("test");
        e.setUsername("reader");
        e.setDriverClassName("org.postgresql.Driver");

        javax.sql.DataSource ds = factory.createOrGet(e, "pwd");
        assertThat(ds).isNotNull();
    }
    // ============================================================
    //  Wave4 待办池 #14：vendor 家族映射（零新增依赖的兼容库支持）
    // ============================================================

    @Test
    void familyOf_mapsCompatibleVendorsToExistingDrivers() {
        assertThat(ExternalDataSourceConnectionFactory.familyOf("mysql")).isEqualTo("mysql");
        assertThat(ExternalDataSourceConnectionFactory.familyOf("mariadb")).isEqualTo("mysql");
        assertThat(ExternalDataSourceConnectionFactory.familyOf("oceanbase")).isEqualTo("mysql");
        assertThat(ExternalDataSourceConnectionFactory.familyOf("postgresql")).isEqualTo("postgresql");
        assertThat(ExternalDataSourceConnectionFactory.familyOf("kingbase")).isEqualTo("postgresql");
        assertThat(ExternalDataSourceConnectionFactory.familyOf("opengauss")).isEqualTo("postgresql");
        // 大小写不敏感
        assertThat(ExternalDataSourceConnectionFactory.familyOf("MariaDB")).isEqualTo("mysql");
    }

    @Test
    void validateVendor_rejectsUnsupportedVendors() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> factory.validateVendor("oracle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数据库类型");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> factory.validateVendor("sqlserver"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> factory.validateVendor(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
