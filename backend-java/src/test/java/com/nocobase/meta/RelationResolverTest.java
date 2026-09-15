package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelationResolverTest {

    private CollectionRepository collectionRepository;
    private DynamicTableManager tableManager;
    private RelationResolver resolver;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        collectionRepository = mock(CollectionRepository.class);
        tableManager = mock(DynamicTableManager.class);
        resolver = new RelationResolver(collectionRepository, tableManager, MAPPER);
    }

    @Test
    void expandRelations_nullFields_doesNothing() {
        resolver.expandRelations(null, new LinkedHashMap<>(), "t1");
        // 无 NPE
    }

    @Test
    void expandRelations_emptyRecord_doesNothing() {
        List<FieldDef> fields = List.of(belongsToField("user"));
        resolver.expandRelations(fields, new LinkedHashMap<>(), "t1");
        // 无 NPE
    }

    @Test
    void expandRelations_belongsTo_expandsToIdTitle() {
        // 目标 collection = "users",title 字段为 "name"
        String usersMeta = metaJson(List.of(
                new FieldDef("name", "text", false, null, null),
                new FieldDef("email", "text", false, null, null)));
        CollectionMetaEntity targetMeta = new CollectionMetaEntity();
        targetMeta.setFieldsJson(usersMeta);
        when(collectionRepository.findByNameAndTenantId("users", "t1"))
                .thenReturn(Optional.of(targetMeta));
        when(tableManager.getRecord(eq("users"), anyString()))
                .thenReturn(Optional.of("{\"data\":{\"name\":\"Alice\",\"email\":\"a@x\"}}"));

        // 主 record: id=userId, 关联字段 user_id 指向 users
        List<FieldDef> fields = List.of(belongsToField("user_id", "users"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", "row-1");
        record.put("user_id", "u-123");

        resolver.expandRelations(fields, record, "t1");

        Object expanded = record.get("user_id");
        assertThat(expanded).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) expanded;
        assertThat(m).containsEntry("id", "u-123").containsEntry("title", "Alice");
    }

    @Test
    void expandRelations_hasMany_expandsToListOfIdTitle() {
        String usersMeta = metaJson(List.of(
                new FieldDef("name", "text", false, null, null)));
        CollectionMetaEntity targetMeta = new CollectionMetaEntity();
        targetMeta.setFieldsJson(usersMeta);
        when(collectionRepository.findByNameAndTenantId("users", "t1"))
                .thenReturn(Optional.of(targetMeta));
        when(tableManager.getRecord(eq("users"), anyString()))
                .thenAnswer(inv -> Optional.of(
                        "{\"data\":{\"name\":\"User-" + inv.getArgument(1) + "\"}}"));

        List<FieldDef> fields = List.of(hasManyField("members", "users"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("members", List.of("u-1", "u-2"));

        resolver.expandRelations(fields, record, "t1");

        Object expanded = record.get("members");
        assertThat(expanded).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) expanded;
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).containsEntry("id", "u-1").containsEntry("title", "User-u-1");
        assertThat(list.get(1)).containsEntry("id", "u-2").containsEntry("title", "User-u-2");
    }

    @Test
    void expandRelations_targetCollectionMissing_keepsOriginal() {
        FieldDef f = new FieldDef("user_id", "belongsTo",
                false, null, Map.of("target", ""));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", "u-1");

        resolver.expandRelations(List.of(f), record, "t1");

        // 没 target → expandRelations 不动原值
        assertThat(record.get("user_id")).isEqualTo("u-1");
    }

    @Test
    void expandRelations_targetMetaMissing_keepsOriginalAsId() {
        when(collectionRepository.findByNameAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.empty());

        FieldDef f = belongsToField("user_id", "users");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", "u-1");

        resolver.expandRelations(List.of(f), record, "t1");

        // titleOf 回退为 id
        Object expanded = record.get("user_id");
        assertThat(expanded).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) expanded;
        assertThat(m).containsEntry("id", "u-1").containsEntry("title", "u-1");
    }

    @Test
    void expandRelations_targetRecordMissing_keepsOriginalAsId() {
        String usersMeta = metaJson(List.of(
                new FieldDef("name", "text", false, null, null)));
        CollectionMetaEntity targetMeta = new CollectionMetaEntity();
        targetMeta.setFieldsJson(usersMeta);
        when(collectionRepository.findByNameAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.of(targetMeta));
        when(tableManager.getRecord(anyString(), anyString())).thenReturn(Optional.empty());

        FieldDef f = belongsToField("user_id", "users");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", "u-1");

        resolver.expandRelations(List.of(f), record, "t1");

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) record.get("user_id");
        assertThat(m).containsEntry("title", "u-1");
    }

    @Test
    void expandRelations_nonRelationField_keptUntouched() {
        FieldDef textField = new FieldDef("name", "text", false, null, null);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", "Alice");

        resolver.expandRelations(List.of(textField), record, "t1");

        assertThat(record.get("name")).isEqualTo("Alice");
    }

    @Test
    void expandRelations_nullRawValue_keptUntouched() {
        FieldDef f = belongsToField("user_id", "users");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", null);

        resolver.expandRelations(List.of(f), record, "t1");

        assertThat(record.get("user_id")).isNull();
    }

    @Test
    void expandRelations_picksTitleFieldInOrder() {
        // 优先 "title",再 "name",最后首个 text
        String meta = metaJson(List.of(
                new FieldDef("title", "text", false, null, null),
                new FieldDef("name", "text", false, null, null),
                new FieldDef("age", "number", false, null, null)));
        CollectionMetaEntity targetMeta = new CollectionMetaEntity();
        targetMeta.setFieldsJson(meta);
        when(collectionRepository.findByNameAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.of(targetMeta));
        when(tableManager.getRecord(anyString(), anyString()))
                .thenReturn(Optional.of("{\"data\":{\"title\":\"Boss\",\"name\":\"A\",\"age\":30}}"));

        FieldDef f = belongsToField("user_id", "users");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", "u-1");

        resolver.expandRelations(List.of(f), record, "t1");

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) record.get("user_id");
        assertThat(m).containsEntry("title", "Boss");
    }

    @Test
    void expandRelations_picksTitleFallbackToFirstText() {
        // 没有 name/title → 第一个 text 字段
        String meta = metaJson(List.of(
                new FieldDef("foo", "text", false, null, null),
                new FieldDef("age", "number", false, null, null)));
        CollectionMetaEntity targetMeta = new CollectionMetaEntity();
        targetMeta.setFieldsJson(meta);
        when(collectionRepository.findByNameAndTenantId(anyString(), anyString()))
                .thenReturn(Optional.of(targetMeta));
        when(tableManager.getRecord(anyString(), anyString()))
                .thenReturn(Optional.of("{\"data\":{\"foo\":\"Bar\",\"age\":30}}"));

        FieldDef f = belongsToField("user_id", "users");
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("user_id", "u-1");

        resolver.expandRelations(List.of(f), record, "t1");

        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) record.get("user_id");
        assertThat(m).containsEntry("title", "Bar");
    }

    // ------- helpers -------

    private FieldDef belongsToField(String name) {
        return new FieldDef(name, "belongsTo", false, null,
                Map.of("target", "users"));
    }

    private FieldDef belongsToField(String name, String target) {
        return new FieldDef(name, "belongsTo", false, null,
                Map.of("target", target));
    }

    private FieldDef hasManyField(String name, String target) {
        return new FieldDef(name, "hasMany", false, null,
                Map.of("target", target));
    }

    private String metaJson(List<FieldDef> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
