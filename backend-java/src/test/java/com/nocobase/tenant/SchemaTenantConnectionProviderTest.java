package com.nocobase.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SchemaTenantConnectionProviderTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private SchemaTenantConnectionProvider provider;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        provider = new SchemaTenantConnectionProvider(dataSource);
    }

    @Test
    void getAnyConnection_returnsConnection() throws SQLException {
        Connection c = provider.getAnyConnection();
        assertThat(c).isSameAs(connection);
    }

    @Test
    void getConnection_setsSearchPathToTenantSchema() throws SQLException {
        provider.getConnection("acme_corp");

        verify(statement).execute("SET search_path TO acme_corp, public");
    }

    @Test
    void getConnection_publicTenant_setsSearchPathToPublic() throws SQLException {
        provider.getConnection(TenantContext.PUBLIC_SCHEMA);

        verify(statement).execute("SET search_path TO public, public");
    }

    @Test
    void getConnection_nullIdentifier_defaultsToPublic() throws SQLException {
        provider.getConnection(null);

        verify(statement).execute("SET search_path TO public, public");
    }

    @Test
    void getConnection_sqlExceptionOnSet_isTolerated() throws SQLException {
        when(statement.execute(anyString())).thenThrow(new SQLException("h2 not supported"));

        // 不应抛 — 应降级
        Connection c = provider.getConnection("acme_corp");
        assertThat(c).isSameAs(connection);
    }

    @Test
    void releaseConnection_resetsSearchPath() throws SQLException {
        provider.releaseConnection("acme_corp", connection);

        verify(statement).execute("SET search_path TO public");
        verify(connection).close();
    }

    @Test
    void releaseConnection_resetFailure_doesNotBlockClose() throws SQLException {
        when(statement.execute(anyString())).thenThrow(new SQLException("not supported"));

        // 不应抛,connection.close() 仍执行
        provider.releaseConnection("acme_corp", connection);
        verify(connection, times(1)).close();
    }

    @Test
    void supportsAggressiveRelease_isFalse() {
        assertThat(provider.supportsAggressiveRelease()).isFalse();
    }

    @Test
    void unwrap_toDataSource_returnsDataSource() {
        Object ds = provider.unwrap(DataSource.class);
        assertThat(ds).isSameAs(dataSource);
    }

    @Test
    void unwrap_toMultiTenantConnectionProvider_returnsSelf() {
        Object p = provider.unwrap(org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider.class);
        assertThat(p).isSameAs(provider);
    }

    @Test
    void unwrap_nullType_throwsIllegalArgument() {
        assertThatThrownBy(() -> provider.unwrap(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unwrap_unsupportedType_throwsUnknownUnwrap() {
        assertThatThrownBy(() -> provider.unwrap(String.class))
                .isInstanceOf(org.hibernate.service.UnknownUnwrapTypeException.class);
    }

    @Test
    void isUnwrappableAs_recognizesKnownTypes() {
        assertThat(provider.isUnwrappableAs(DataSource.class)).isTrue();
        assertThat(provider.isUnwrappableAs(
                org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider.class)).isTrue();
        assertThat(provider.isUnwrappableAs(String.class)).isFalse();
        assertThat(provider.isUnwrappableAs(null)).isFalse();
    }

    @Test
    void getConnection_invalidSchema_rejected() {
        // SQL 注入防御:标识符无法参数化,必须白名单
        assertThatThrownBy(() -> provider.getConnection("acme'; DROP TABLE x;--"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法 schema 名");
    }

    @Test
    void getConnection_upperCaseSchema_rejected() {
        assertThatThrownBy(() -> provider.getConnection("Acme"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getConnection_numericLeadingSchema_rejected() {
        assertThatThrownBy(() -> provider.getConnection("1acme"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getConnection_tooLongSchema_rejected() {
        String longName = "a".repeat(64);
        assertThatThrownBy(() -> provider.getConnection(longName))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

class SchemaTenantConnectionProviderNoArgTest {

    @Test
    void noArgConstructor_usesSharedDataSource() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement st = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(st);

        // 先通过带参构造触发 sharedDataSource 注入
        new SchemaTenantConnectionProvider(ds);
        // 再用无参构造 — 拿共享的 DataSource
        SchemaTenantConnectionProvider p = new SchemaTenantConnectionProvider();
        p.getConnection("acme_corp");

        verify(st).execute("SET search_path TO acme_corp, public");
    }
}
