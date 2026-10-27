package com.nocobase.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.common.ExpressionEvaluator;
import com.nocobase.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Wave 4 C2: 集合元数据版本管理测试。
 */
class CollectionVersionServiceTest {

    @Mock private CollectionRepository repository;
    @Mock private DynamicTableManager tableManager;
    @Mock private AsyncMigrationService migrationService;
    @Mock private CollectionVersionRepository versionRepository;
    @Mock private InverseRelationManager inverseManager;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CollectionService service;

    private final UUID collectionId = UUID.randomUUID();
    private final String tenantId = "tenant_001";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TenantContext.set(tenantId);
        service = new CollectionService(
                repository, tableManager, migrationService, objectMapper,
                new ExpressionEvaluator(), null, inverseManager,
                null, null, null, versionRepository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private CollectionMetaEntity metaWith(List<FieldDef> fields) {
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setId(collectionId);
        meta.setName("orders");
        meta.setTenantId(tenantId);
        try {
            meta.setFieldsJson(objectMapper.writeValueAsString(fields));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return meta;
    }

    @Test
    void listVersions_returnsSummaryDescending() {
        when(repository.findByNameAndTenantId("orders", tenantId)).thenReturn(java.util.Optional.of(metaWith(List.of())));

        CollectionVersionEntity v2 = new CollectionVersionEntity();
        v2.setVersion(2);
        v2.setChangeDesc("addField: amount");
        CollectionVersionEntity v1 = new CollectionVersionEntity();
        v1.setVersion(1);
        v1.setChangeDesc("addField: title");
        when(versionRepository.findByCollectionIdOrderByVersionDesc(collectionId))
                .thenReturn(List.of(v2, v1));

        List<Map<String, Object>> result = service.listVersions("orders", tenantId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("version")).isEqualTo(2);
        assertThat(result.get(1).get("changeDesc")).isEqualTo("addField: title");
    }

    @Test
    void diffVersions_detectsAddedRemovedChanged() throws Exception {
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setId(collectionId);
        meta.setTenantId(tenantId);
        when(repository.findByNameAndTenantId("orders", tenantId)).thenReturn(java.util.Optional.of(meta));

        List<FieldDef> v1Fields = List.of(
                new FieldDef("title", "text", true, "标题", null, false, false, null, null),
                new FieldDef("amount", "number", false, "金额", null, false, false, null, null));
        List<FieldDef> v2Fields = List.of(
                new FieldDef("title", "text", true, "标题", null, false, false, null, null),
                new FieldDef("amount", "number", true, "金额", null, false, false, null, null),
                new FieldDef("note", "text", false, "备注", null, false, false, null, null));

        CollectionVersionEntity ev1 = new CollectionVersionEntity();
        ev1.setFieldsJson(objectMapper.writeValueAsString(v1Fields));
        CollectionVersionEntity ev2 = new CollectionVersionEntity();
        ev2.setFieldsJson(objectMapper.writeValueAsString(v2Fields));

        when(versionRepository.findByCollectionIdAndVersion(collectionId, 1)).thenReturn(ev1);
        when(versionRepository.findByCollectionIdAndVersion(collectionId, 2)).thenReturn(ev2);

        List<Map<String, Object>> diffs = service.diffVersions("orders", 1, 2, tenantId);

        assertThat(diffs).extracting(d -> d.get("op") + ":" + d.get("field"))
                .containsExactlyInAnyOrder("added:note", "changed:amount");
    }

    @Test
    void rollbackToVersion_writesHistoricalFields_andRecordsNewVersion() throws Exception {
        List<FieldDef> historical = List.of(
                new FieldDef("title", "text", true, "标题", null, false, false, null, null));
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("title", "text", true, "标题", null, false, false, null, null),
                new FieldDef("extra", "number", false, "多余", null, false, false, null, null)));
        when(repository.findByNameAndTenantId("orders", tenantId)).thenReturn(java.util.Optional.of(meta));

        CollectionVersionEntity ev1 = new CollectionVersionEntity();
        ev1.setFieldsJson(objectMapper.writeValueAsString(historical));
        when(versionRepository.findByCollectionIdAndVersion(collectionId, 1)).thenReturn(ev1);
        when(versionRepository.findMaxVersionByCollectionId(collectionId)).thenReturn(5);

        service.rollbackToVersion("orders", 1, tenantId, UUID.randomUUID());

        List<FieldDef> after = objectMapper.readValue(meta.getFieldsJson(),
                new com.fasterxml.jackson.core.type.TypeReference<List<FieldDef>>() {});
        assertThat(after).hasSize(1);
        assertThat(after.get(0).name()).isEqualTo("title");

        verify(versionRepository).save(argThat(v ->
                v.getVersion() == 6 && v.getChangeDesc().equals("rollbackTo: v1")));
    }

    @Test
    void getVersionFields_unknownVersion_throws404() {
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setId(collectionId);
        meta.setTenantId(tenantId);
        when(repository.findByNameAndTenantId("orders", tenantId)).thenReturn(java.util.Optional.of(meta));
        when(versionRepository.findByCollectionIdAndVersion(eq(collectionId), eq(99))).thenReturn(null);

        assertThatThrownBy(() -> service.getVersionFields("orders", 99, tenantId))
                .hasMessageContaining("版本不存在");
    }

    @Test
    void listVersions_crossTenant_forbidden() {
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setId(collectionId);
        meta.setTenantId("other_tenant");
        when(repository.findByNameAndTenantId("orders", tenantId)).thenReturn(java.util.Optional.of(meta));

        assertThatThrownBy(() -> service.listVersions("orders", tenantId))
                .hasMessageContaining("无权访问");
    }

    @Test
    void addField_createsVersionSnapshot() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("title", "text", true, "标题", null, false, false, null, null)));
        when(repository.findByNameAndTenantId("orders", tenantId)).thenReturn(java.util.Optional.of(meta));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(versionRepository.findMaxVersionByCollectionId(collectionId)).thenReturn(0);

        service.addField("orders", new FieldDef("amount", "number", false, "金额", null, false, false, null, null),
                tenantId, UUID.randomUUID());

        verify(versionRepository).save(argThat(v ->
                v.getVersion() == 1 && v.getChangeDesc().equals("addField: amount")));
    }
}
