package com.nocobase.integration.datasource;

import com.nocobase.audit.AuditService;
import com.nocobase.meta.CollectionRepository;
import com.nocobase.meta.FieldDef;
import com.nocobase.integration.credential.CredentialCryptoService;
import com.nocobase.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B3 返工：外部数据源 Controller 关键路径单测。
 * 覆盖 create（加密存储 + 租户隔离）、get/listTables 的租户去硬编码、list()。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExternalDataSourceControllerTest {

    @Mock private ExternalDataSourceService service;
    @Mock private AuditService auditService;

    private ExternalDataSourceController controller;

    @BeforeEach
    void setUp() {
        controller = new ExternalDataSourceController(service, auditService);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private ExternalDataSourceEntity sample(UUID id, String tenant) {
        ExternalDataSourceEntity e = new ExternalDataSourceEntity();
        e.setId(id);
        e.setTenantId(tenant);
        e.setName("mysqlds");
        e.setVendor("mysql");
        e.setHost("10.0.0.1");
        e.setPort(3306);
        e.setDatabaseName("shopdb");
        e.setUsername("reader");
        e.setPasswordEnc("enc");
        e.setStatus("ACTIVE");
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return e;
    }

    @Test
    void create_encryptsPassword_andUsesTenantContext() {
        UUID dsId = UUID.randomUUID();
        ExternalDataSourceEntity saved = sample(dsId, "tenant_a");
        when(service.createDataSource(eq("tenant_a"), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), eq("p@ss"), isNull(), isNull()))
                .thenReturn(saved);

        TenantContext.set("tenant_a");
        var resp = controller.create(new ExternalDataSourceController.CreateDataSourceRequest(
                "mysqlds", "mysql", "10.0.0.1", 3306, "shopdb", "reader", "p@ss", null));

        assertNotNull(resp);
        assertEquals(201, resp.getStatusCodeValue());
        // 响应体绝不暴露密码
        assertFalse(String.valueOf(resp.getBody()).contains("p@ss"));

        // 关键：Service 收到的 tenantId 来自 TenantContext（非 "system"）
        verify(service).createDataSource(eq("tenant_a"), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), eq("p@ss"), isNull(), isNull());
    }

    @Test
    void create_duplicateRejected_propagatesIAE() {
        when(service.createDataSource(anyString(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("同名数据源已存在"));

        TenantContext.set("tenant_a");
        assertThrows(IllegalArgumentException.class, () ->
                controller.create(new ExternalDataSourceController.CreateDataSourceRequest(
                        "dup", "mysql", "h", 3306, "db", "u", "p", null)));
    }

    @Test
    void get_usesTenantContextNotSystem() {
        UUID id = UUID.randomUUID();
        when(service.getDataSource(id, "tenant_a")).thenReturn(Optional.of(sample(id, "tenant_a")));

        TenantContext.set("tenant_a");
        Map<String, Object> r = controller.get(id);
        assertEquals(0, r.get("code"));
        verify(service).getDataSource(id, "tenant_a");
        // 修复前此方法传 "system"，验证不再出现
        verify(service, never()).getDataSource(any(), eq("system"));
    }

    @Test
    void listTables_usesTenantContext() {
        UUID id = UUID.randomUUID();
        when(service.listTables(id, "tenant_b")).thenReturn(List.of("t1", "t2"));

        TenantContext.set("tenant_b");
        Map<String, Object> r = controller.listTables(id);
        assertEquals(List.of("t1", "t2"), r.get("data"));
        verify(service).listTables(id, "tenant_b");
    }

    @Test
    void list_returnsTenantDataSources() {
        UUID id = UUID.randomUUID();
        when(service.listDataSources("tenant_c")).thenReturn(List.of(sample(id, "tenant_c")));

        TenantContext.set("tenant_c");
        Map<String, Object> r = controller.list();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
        assertEquals(1, data.size());
        // toView 不暴露密码字段
        assertFalse(data.get(0).containsKey("password"));
        verify(service).listDataSources("tenant_c");
    }
}
