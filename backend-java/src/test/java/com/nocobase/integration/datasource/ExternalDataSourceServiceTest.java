package com.nocobase.integration.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.CredentialCryptoService;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionRepository;
import com.nocobase.meta.FieldDef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * {@link ExternalDataSourceService} 单元测试（PHASE 100 覆盖率补测）。
 *
 * <p>JDBC 相关路径（{@code SELECT 1} 连通性测试、information_schema 读列）用
 * <b>H2 PostgreSQL 模式</b>真实执行 —— 避免 mock JdbcTemplate（Service 内部
 * {@code new JdbcTemplate(ds)}，无法注入替身）。其余分支用 Mockito。
 */
class ExternalDataSourceServiceTest {

    private ExternalDataSourceRepository datasourceRepository;
    private ExternalDataSourceConnectionFactory connectionFactory;
    private CredentialCryptoService cryptoService;
    private CollectionRepository collectionRepository;
    private ExternalDataSourceService service;
    private JdbcTemplate h2;

    @BeforeEach
    void setUp() {
        datasourceRepository = mock(ExternalDataSourceRepository.class);
        connectionFactory = mock(ExternalDataSourceConnectionFactory.class);
        cryptoService = mock(CredentialCryptoService.class);
        collectionRepository = mock(CollectionRepository.class);
        service = new ExternalDataSourceService(datasourceRepository, connectionFactory,
                cryptoService, collectionRepository, new ObjectMapper());

        DataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                // DATABASE_TO_LOWER 让 H2 对齐 PostgreSQL 的大小写语义：
                // 未加引号标识符存为小写（否则 column_name 会返回 ID 而非 id）
                "jdbc:h2:mem:extds_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        h2 = new JdbcTemplate(ds);
        // 列名加引号保持小写（配合 DATABASE_TO_LOWER）。
        // 类型选 BIGINT/SMALLINT 而非 NUMERIC：H2 的 information_schema.data_type
        // 对 NUMERIC 会带精度后缀（如 "NUMERIC(18,2)"），而真实 PostgreSQL 返回
        // 无后缀的 'numeric' —— 用 BIGINT 让类型映射断言在 H2 上确定成立。
        h2.execute("CREATE TABLE \"users\" (id BIGINT NOT NULL, name VARCHAR(50), ok SMALLINT)");
    }

    private ExternalDataSourceEntity entity(UUID id, String tenantId, String vendor, String schema) {
        ExternalDataSourceEntity e = new ExternalDataSourceEntity();
        e.setId(id);
        e.setTenantId(tenantId);
        e.setVendor(vendor);
        e.setDatabaseName(schema);
        e.setPasswordEnc("ENC");
        return e;
    }

    private void stubFind(UUID id, String tenantId, ExternalDataSourceEntity e) {
        when(datasourceRepository.findByIdAndTenantId(id, tenantId)).thenReturn(Optional.ofNullable(e));
    }

    private void stubH2() {
        when(connectionFactory.createOrGet(any(), anyString()))
                .thenAnswer(inv -> h2.getDataSource());
    }

    // ================= 创建 =================

    @Test
    @DisplayName("createDataSource：租户为空 → 拒绝")
    void create_blankTenant_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.createDataSource(
                "  ", "ds", "mysql", "h", 3306, "db", "u", "p", null, UUID.randomUUID()));
    }

    @Test
    @DisplayName("createDataSource：同名已存在 → 拒绝，且不落库")
    void create_duplicateName_rejected() {
        when(datasourceRepository.existsByTenantIdAndName("t1", "ds")).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service.createDataSource(
                "t1", "ds", "mysql", "h", 3306, "db", "u", "p", null, UUID.randomUUID()));
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    @DisplayName("createDataSource：密码加密落库，同步配置默认 MANUAL")
    void create_success_encryptsPassword() {
        UUID uid = UUID.randomUUID();
        when(datasourceRepository.existsByTenantIdAndName("t1", "ds")).thenReturn(false);
        when(cryptoService.encrypt("plain")).thenReturn("CIPHER");
        when(datasourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExternalDataSourceEntity saved = service.createDataSource(
                "t1", "ds", "MySQL", "h", 3306, "db", "u", "plain", null, uid);

        verify(cryptoService).encrypt("plain");
        assertEquals("CIPHER", saved.getPasswordEnc());
        assertEquals("mysql", saved.getVendor());          // vendor 归一化为小写
        assertEquals("ACTIVE", saved.getStatus());
        assertFalse(saved.getSyncEnabled());
        assertEquals("MANUAL", saved.getSyncMode());
        assertNotNull(saved.getUpdatedAt());               // updatedAt 非空（列 NOT NULL）
    }

    @Test
    @DisplayName("createDataSource：syncEnabled=true 时同步字段大写归一")
    void create_withSyncConfig() {
        when(datasourceRepository.existsByTenantIdAndName("t1", "ds")).thenReturn(false);
        when(cryptoService.encrypt("plain")).thenReturn("CIPHER");
        when(datasourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExternalDataSourceEntity saved = service.createDataSource("t1", "ds", "mysql", "h", 3306, "db",
                "u", "plain", null, UUID.randomUUID(), true, "incremental", "0 0 * * * ?", "updated_at");

        assertTrue(saved.getSyncEnabled());
        assertEquals("INCREMENTAL", saved.getSyncMode());
        assertEquals("0 0 * * * ?", saved.getSyncCron());
        assertEquals("updated_at", saved.getIncrementalKey());
    }

    // ================= 连接测试 =================

    @Test
    @DisplayName("testConnection：SELECT 1 成功 → true")
    void testConnection_success() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "postgresql", "public"));
        when(cryptoService.decrypt("ENC")).thenReturn("pw");
        stubH2();

        assertTrue(service.testConnection(id, "t1"));
    }

    @Test
    @DisplayName("testConnection：密码解密失败 → false（不抛）")
    void testConnection_decryptFailed_returnsFalse() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "postgresql", "public"));
        when(cryptoService.decrypt("ENC")).thenThrow(new RuntimeException("bad key"));

        assertFalse(service.testConnection(id, "t1"));
    }

    @Test
    @DisplayName("testConnection：建池/执行异常 → false")
    void testConnection_jdbcError_returnsFalse() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "postgresql", "public"));
        when(cryptoService.decrypt("ENC")).thenReturn("pw");
        when(connectionFactory.createOrGet(any(), anyString()))
                .thenThrow(new IllegalStateException("pool error"));

        assertFalse(service.testConnection(id, "t1"));
    }

    @Test
    @DisplayName("testConnection：找不到数据源 → IllegalArgumentException")
    void testConnection_notFound() {
        UUID id = UUID.randomUUID();
        when(datasourceRepository.findByIdAndTenantId(id, "t1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.testConnection(id, "t1"));
    }

    // ================= 读表结构 =================

    @Test
    @DisplayName("readTableColumns：真实读取 information_schema 并映射字段类型")
    void readTableColumns_mapsTypes() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "postgresql", "public"));
        when(cryptoService.decrypt("ENC")).thenReturn("pw");
        stubH2();

        List<FieldDef> fields = service.readTableColumns(id, "t1", "users");

        assertEquals(3, fields.size());
        FieldDef idField = fields.get(0);
        assertEquals("id", idField.name());
        assertEquals("number", idField.type());     // BIGINT → number
        assertTrue(idField.required());             // NOT NULL → required
        FieldDef nameField = fields.get(1);
        assertEquals("name", nameField.name());
        assertEquals("text", nameField.type());     // CHARACTER VARYING / VARCHAR → text
        assertFalse(nameField.required());
        FieldDef okField = fields.get(2);
        assertEquals("ok", okField.name());
        assertEquals("number", okField.type());     // SMALLINT → number
        assertFalse(okField.required());
    }

    @Test
    @DisplayName("readTableColumns：vendor 非法 → validateVendor 抛错，且不解密密码")
    void readTableColumns_unsupportedVendor() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "oracle", "X"));
        doThrow(new IllegalArgumentException("不支持的数据库类型：oracle"))
                .when(connectionFactory).validateVendor("oracle");

        assertThrows(IllegalArgumentException.class, () -> service.readTableColumns(id, "t1", "t"));
        verify(cryptoService, never()).decrypt(anyString());
    }

    @Test
    @DisplayName("readTableColumns / listTables / delete / createVirtualCollection：找不到 → IllegalArgumentException")
    void notFound_pathsThrow() {
        UUID id = UUID.randomUUID();
        when(datasourceRepository.findByIdAndTenantId(id, "t1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.readTableColumns(id, "t1", "t"));
        assertThrows(IllegalArgumentException.class, () -> service.listTables(id, "t1"));
        assertThrows(IllegalArgumentException.class, () -> service.deleteDataSource(id, "t1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.createVirtualCollection(id, "t1", "t", "c", "T", "D", UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> service.syncDatasource(id, "t1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSyncConfig(id, "t1", true, "FULL", null, null));
    }

    // ================= 虚拟 Collection =================

    @Test
    @DisplayName("createVirtualCollection：Collection 同名已存在 → 拒绝")
    void createVirtualCollection_duplicate() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "postgresql", "public"));
        when(cryptoService.decrypt("ENC")).thenReturn("pw");
        stubH2();
        when(collectionRepository.existsByName("users_view")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.createVirtualCollection(id, "t1", "users", "users_view", "T", "D", UUID.randomUUID()));
    }

    @Test
    @DisplayName("createVirtualCollection：成功 → 标记只读并回写 lastSyncedAt")
    void createVirtualCollection_success() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "postgresql", "public"));
        when(cryptoService.decrypt("ENC")).thenReturn("pw");
        stubH2();
        when(collectionRepository.existsByName("users_view")).thenReturn(false);
        when(collectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CollectionMetaEntity meta = service.createVirtualCollection(
                id, "t1", "users", "users_view", "用户视图", "外部用户表", UUID.randomUUID());

        assertTrue(meta.getReadOnly());
        assertEquals(id, meta.getDatasourceId());
        assertEquals("users", meta.getExternalTableName());
        assertNotNull(meta.getFieldsJson());
        assertTrue(meta.getFieldsJson().contains("\"name\":\"id\""));
        verify(datasourceRepository).save(any(ExternalDataSourceEntity.class));  // 回写 lastSyncedAt
    }

    // ================= 同步 =================

    @Test
    @DisplayName("syncDatasource：水位无变化 → 跳过重刷，返回 false")
    void sync_incrementalNoChange_returnsFalse() {
        UUID id = UUID.randomUUID();
        ExternalDataSourceEntity e = entity(id, "t1", "postgresql", "public");
        e.setSyncMode("INCREMENTAL");
        e.setIncrementalKey("updated_at");
        e.setLastSyncCursor("");          // 与探测到的空水位一致
        stubFind(id, "t1", e);
        when(collectionRepository.findByDatasourceId(id)).thenReturn(List.of());   // 无映射 → 水位 ""

        assertFalse(service.syncDatasource(id, "t1"));
    }

    @Test
    @DisplayName("syncDatasource：INCREMENTAL 未配 incrementalKey → 降级 FULL 并刷新")
    void sync_incrementalWithoutKey_fallsBackToFull() {
        UUID id = UUID.randomUUID();
        ExternalDataSourceEntity e = entity(id, "t1", "postgresql", "public");
        e.setSyncMode("INCREMENTAL");
        stubFind(id, "t1", e);
        when(collectionRepository.findByDatasourceId(id)).thenReturn(null);

        assertTrue(service.syncDatasource(id, "t1"));
    }

    @Test
    @DisplayName("syncDatasource：FULL 模式刷新已映射 Collection 的结构")
    void sync_full_refreshesMappings() {
        UUID id = UUID.randomUUID();
        ExternalDataSourceEntity e = entity(id, "t1", "postgresql", "public");
        e.setSyncMode("FULL");
        stubFind(id, "t1", e);
        when(cryptoService.decrypt("ENC")).thenReturn("pw");
        stubH2();

        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setName("users_view");
        meta.setExternalTableName("users");
        when(collectionRepository.findByDatasourceId(id)).thenReturn(List.of(meta));

        assertTrue(service.syncDatasource(id, "t1"));
        assertNotNull(meta.getFieldsJson());
        verify(collectionRepository).save(meta);
    }

    // ================= 配置与删除 =================

    @Test
    @DisplayName("updateSyncConfig：更新字段并刷新 updatedAt")
    void updateSyncConfig() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "mysql", "db"));
        ExternalDataSourceEntity result = service.updateSyncConfig(id, "t1", true, "FULL", "0 0 * * * ?", "id");
        assertTrue(result.getSyncEnabled());
        assertEquals("FULL", result.getSyncMode());
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    @DisplayName("deleteDataSource：清理连接池后删记录")
    void deleteDataSource() {
        UUID id = UUID.randomUUID();
        stubFind(id, "t1", entity(id, "t1", "mysql", "db"));
        service.deleteDataSource(id, "t1");
        verify(connectionFactory).invalidate(id.toString());
        verify(datasourceRepository).delete(any(ExternalDataSourceEntity.class));
    }

    @Test
    @DisplayName("listDataSources / getDataSource：透传仓储")
    void listAndGet() {
        UUID id = UUID.randomUUID();
        when(datasourceRepository.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of());
        assertEquals(0, service.listDataSources("t1").size());

        when(datasourceRepository.findByIdAndTenantId(id, "t1")).thenReturn(Optional.of(entity(id, "t1", "mysql", "db")));
        assertTrue(service.getDataSource(id, "t1").isPresent());

        when(datasourceRepository.findByIdAndTenantId(eq(id), eq("other"))).thenReturn(Optional.empty());
        assertFalse(service.getDataSource(id, "other").isPresent());
    }
}
