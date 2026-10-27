package com.nocobase.integration.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.integration.credential.CredentialCryptoService;
import com.nocobase.meta.CollectionMetaEntity;
import com.nocobase.meta.CollectionRepository;
import com.nocobase.meta.FieldDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHASE B B3: ExternalDataSourceService 单元测试。
 *
 * <p>覆盖：数据源查询隔离、删除级联清理连接池、虚拟 Collection 创建（只读标记 +
 * 外部表名 + 字段快照）、重复 Collection 拒绝、不支持 vendor 报错。
 *
 * <p>连接工厂以 Mockito mock 替代真实 JDBC，避免测试依赖外部数据库。
 */
class ExternalDataSourceServiceTest {

    private ExternalDataSourceRepository repository;
    private ExternalDataSourceConnectionFactory connectionFactory;
    private CredentialCryptoService cryptoService;
    private CollectionRepository collectionRepository;
    private ExternalDataSourceService service;

    private static final String TENANT = "tenant-a";
    private final UUID dsId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ExternalDataSourceRepository.class);
        connectionFactory = mock(ExternalDataSourceConnectionFactory.class);
        cryptoService = mock(CredentialCryptoService.class);
        collectionRepository = mock(CollectionRepository.class);
        service = new ExternalDataSourceService(
                repository, connectionFactory, cryptoService, collectionRepository, new ObjectMapper());
    }

    private ExternalDataSourceEntity sampleEntity() {
        ExternalDataSourceEntity e = new ExternalDataSourceEntity();
        e.setId(dsId);
        e.setTenantId(TENANT);
        e.setName("外部订单库");
        e.setVendor("postgresql");
        e.setHost("db.example.com");
        e.setPort(5432);
        e.setDatabaseName("orders");
        e.setUsername("reader");
        e.setPasswordEnc("enc:xxx");
        e.setStatus("active");
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    @DisplayName("getDataSource: 跨租户查询返回空（租户隔离）")
    void getDataSource_isolatesTenant() {
        when(repository.findByIdAndTenantId(dsId, "other")).thenReturn(Optional.empty());

        assertThat(service.getDataSource(dsId, "other")).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("deleteDataSource: 删除前失效连接池缓存并删除记录")
    void deleteDataSource_invalidatesPool() {
        ExternalDataSourceEntity e = sampleEntity();
        when(repository.findByIdAndTenantId(dsId, TENANT)).thenReturn(Optional.of(e));

        service.deleteDataSource(dsId, TENANT);

        verify(connectionFactory).invalidate(dsId.toString());
        verify(repository).delete(e);
    }

    @Test
    @DisplayName("deleteDataSource: 数据源不存在时抛异常且不触碰连接池")
    void deleteDataSource_notFound() {
        when(repository.findByIdAndTenantId(dsId, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteDataSource(dsId, TENANT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("外部数据源不存在");

        verify(connectionFactory, never()).invalidate(anyString());
    }

    @Test
    @DisplayName("createVirtualCollection: 标记只读 + 记录外部表名 + 快照字段")
    void createVirtualCollection_marksReadOnly() throws Exception {
        ExternalDataSourceEntity e = sampleEntity();
        when(repository.findByIdAndTenantId(dsId, TENANT)).thenReturn(Optional.of(e));
        when(collectionRepository.existsByName("ext_orders")).thenReturn(false);
        when(collectionRepository.save(any(CollectionMetaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 桩掉列读取（避免真实 JDBC）
        ExternalDataSourceService spy = org.mockito.Mockito.spy(service);
        List<FieldDef> cols = List.of(
                new FieldDef("order_no", "text", true, "order_no", null),
                new FieldDef("amount", "number", false, "amount", null));
        org.mockito.Mockito.doReturn(cols).when(spy).readTableColumns(dsId, TENANT, "t_orders");

        CollectionMetaEntity saved = spy.createVirtualCollection(
                dsId, TENANT, "t_orders", "ext_orders", "外部订单", "只读映射", null);

        assertThat(saved.getReadOnly()).isTrue();
        assertThat(saved.getDatasourceId()).isEqualTo(dsId);
        assertThat(saved.getExternalTableName()).isEqualTo("t_orders");
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getFieldsJson()).contains("order_no").contains("amount");

        ArgumentCaptor<ExternalDataSourceEntity> cap =
                ArgumentCaptor.forClass(ExternalDataSourceEntity.class);
        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getLastSyncedAt()).isNotNull();
    }

    @Test
    @DisplayName("createVirtualCollection: Collection 重名时拒绝创建")
    void createVirtualCollection_rejectsDuplicate() {
        ExternalDataSourceEntity e = sampleEntity();
        when(repository.findByIdAndTenantId(dsId, TENANT)).thenReturn(Optional.of(e));
        when(collectionRepository.existsByName("dup")).thenReturn(true);

        ExternalDataSourceService spy = org.mockito.Mockito.spy(service);
        org.mockito.Mockito.doReturn(List.<FieldDef>of())
                .when(spy).readTableColumns(any(), anyString(), anyString());

        assertThatThrownBy(() -> spy.createVirtualCollection(
                dsId, TENANT, "t", "dup", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collection 已存在");

        verify(collectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("readTableColumns: 不支持的 vendor 抛异常（validateVendor 真实逻辑）")
    void readTableColumns_unsupportedVendor() {
        ExternalDataSourceEntity e = sampleEntity();
        e.setVendor("oracle");
        when(repository.findByIdAndTenantId(dsId, TENANT)).thenReturn(Optional.of(e));
        // 让 mock 的 validateVendor 走真实校验逻辑（否则会静默通过导致 JdbcTemplate 构造 NPE）
        org.mockito.Mockito.doCallRealMethod()
                .when(connectionFactory).validateVendor(anyString());

        assertThatThrownBy(() -> service.readTableColumns(dsId, TENANT, "t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的数据库类型");
    }

    @Test
    @DisplayName("testConnection: 数据源不存在时抛异常")
    void testConnection_notFound() {
        when(repository.findByIdAndTenantId(dsId, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.testConnection(dsId, TENANT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
