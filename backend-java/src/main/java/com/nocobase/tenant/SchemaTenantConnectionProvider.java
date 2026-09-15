package com.nocobase.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.service.UnknownUnwrapTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 {@code SET search_path} 的多租户连接提供者(Week 41 D6 Step G2 — ADR-007).
 *
 * <p>每次取连接时把 search_path 切到当前租户 schema,并保留 {@code public} 作为兜底 —
 * 这样默认租户(映射到 public)与存量代码行为完全一致,实现<strong>零迁移</strong>。
 *
 * <p><strong>兼容性降级</strong>:若数据库不支持 {@code SET search_path}(如 H2),
 * 仅记录警告并退化为默认 schema,不阻断启动 —— 保证测试环境不受影响。
 *
 * @see TenantIdentifierResolver
 */
@Component
public class SchemaTenantConnectionProvider implements MultiTenantConnectionProvider {

    private static final Logger log = LoggerFactory.getLogger(SchemaTenantConnectionProvider.class);

    /** schema 名白名单。标识符无法参数化,必须校验以防 SQL 注入。 */
    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final DataSource dataSource;

    public SchemaTenantConnectionProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public Connection getConnection(Object tenantIdentifier) throws SQLException {
        String schema = safeSchema(tenantIdentifier);
        Connection conn = getAnyConnection();
        try (Statement st = conn.createStatement()) {
            // 保留 public 兜底:默认租户与存量对象始终可见
            st.execute("SET search_path TO " + schema + ", public");
        } catch (SQLException e) {
            // 不支持(如 H2)时降级,不阻断 —— 退化为单 schema 行为
            log.warn("[tenant] SET search_path 失败,退化为默认 schema(数据库可能不支持): {}", e.getMessage());
        }
        return conn;
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public void releaseConnection(Object tenantIdentifier, Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            // 归还前复位,避免连接池复用时串租户
            st.execute("SET search_path TO public");
        } catch (SQLException e) {
            log.debug("[tenant] 复位 search_path 失败(可忽略): {}", e.getMessage());
        } finally {
            connection.close();
        }
    }

    @Override
    public boolean supportsAggressiveRelease() {
        // 归还前需复位 search_path,不能走激进释放路径
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return unwrapType != null
                && (MultiTenantConnectionProvider.class.isAssignableFrom(unwrapType)
                    || DataSource.class.isAssignableFrom(unwrapType)
                    || unwrapType.isInstance(this));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> unwrapType) {
        if (unwrapType == null) {
            throw new IllegalArgumentException("unwrapType 不能为空");
        }
        if (unwrapType.isInstance(this)
                || MultiTenantConnectionProvider.class.isAssignableFrom(unwrapType)) {
            return (T) this;
        }
        if (DataSource.class.isAssignableFrom(unwrapType)) {
            return (T) dataSource;
        }
        throw new UnknownUnwrapTypeException(unwrapType);
    }

    private static String safeSchema(Object tenantIdentifier) {
        String s = tenantIdentifier == null ? TenantContext.PUBLIC_SCHEMA : tenantIdentifier.toString();
        if (!SCHEMA_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "非法 schema 名(必须匹配 ^[a-z][a-z0-9_]{0,62}$): " + s);
        }
        return s;
    }
}
