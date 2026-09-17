package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * US-003:CollectionService.applyFieldConstraints 单元测试。
 *
 * <p>覆盖语义:
 * <ul>
 *   <li>defaultValue:缺失/为空时自动填充,已有值不被覆盖</li>
 *   <li>required:填充后仍为空 → 400</li>
 *   <li>unique:值非 null 且库中存在同值 → 409;null 不参与判定</li>
 *   <li>primaryKey:= 必填 + 唯一</li>
 * </ul>
 */
class CollectionServiceFieldConstraintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DynamicTableManager tableManager;
    private CollectionService service;

    @BeforeEach
    void setUp() {
        tableManager = Mockito.mock(DynamicTableManager.class);
        service = new CollectionService(null, tableManager, null, objectMapper);
    }

    /** 构造 meta:字段定义序列化进 fieldsJson。 */
    private CollectionMetaEntity metaWith(List<FieldDef> fields) throws Exception {
        CollectionMetaEntity meta = new CollectionMetaEntity();
        meta.setName("users");
        meta.setTenantId("t1");
        meta.setFieldsJson(objectMapper.writeValueAsString(fields));
        return meta;
    }

    @Test
    void defaultValue_applied_whenFieldMissing() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("status", "text", false, "状态", null, false, false, "active")));

        Map<String, Object> out = service.applyFieldConstraints(meta, new HashMap<>(), null);

        assertThat(out).containsEntry("status", "active");
    }

    @Test
    void defaultValue_applied_whenFieldBlank() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("status", "text", false, "状态", null, false, false, "active")));
        Map<String, Object> data = new HashMap<>();
        data.put("status", "   "); // 空白视为空

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("status", "active");
    }

    @Test
    void defaultValue_doesNotOverrideExistingValue() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("status", "text", false, "状态", null, false, false, "active")));
        Map<String, Object> data = new HashMap<>();
        data.put("status", "archived");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("status", "archived");
    }

    @Test
    void required_missing_throwsBadRequest() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("email", "text", true, "邮箱", null, false, false, null)));

        assertThatThrownBy(() -> service.applyFieldConstraints(meta, new HashMap<>(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void required_present_passes() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("email", "text", true, "邮箱", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("email", "a@b.com");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("email", "a@b.com");
    }

    @Test
    void unique_duplicate_throwsConflict() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("email", "text", false, "邮箱", null, false, true, null)));
        when(tableManager.existsByFieldValue(eq("users"), eq("email"), eq("dup@x.com"), any()))
                .thenReturn(true);
        Map<String, Object> data = new HashMap<>();
        data.put("email", "dup@x.com");

        assertThatThrownBy(() -> service.applyFieldConstraints(meta, data, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unique_noDuplicate_passes() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("email", "text", false, "邮箱", null, false, true, null)));
        when(tableManager.existsByFieldValue(anyString(), anyString(), anyString(), any()))
                .thenReturn(false);
        Map<String, Object> data = new HashMap<>();
        data.put("email", "new@x.com");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("email", "new@x.com");
    }

    @Test
    void unique_nullValue_skipsCheck() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("email", "text", false, "邮箱", null, false, true, null)));
        // 不 stub(若被调用默认 false),重点:不应因 null 触发冲突
        Map<String, Object> data = new HashMap<>();
        data.put("email", null);

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsKey("email");
        assertThat(out.get("email")).isNull();
    }

    @Test
    void primaryKey_missing_throwsBadRequest() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("code", "text", false, "编码", null, true, false, null)));

        assertThatThrownBy(() -> service.applyFieldConstraints(meta, new HashMap<>(), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void primaryKey_duplicate_throwsConflict() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("code", "text", false, "编码", null, true, false, null)));
        when(tableManager.existsByFieldValue(eq("users"), eq("code"), eq("C1"), any()))
                .thenReturn(true);
        Map<String, Object> data = new HashMap<>();
        data.put("code", "C1");

        assertThatThrownBy(() -> service.applyFieldConstraints(meta, data, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void update_passesExcludeId_toAvoidSelfConflict() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("email", "text", false, "邮箱", null, false, true, null)));
        // 自身记录:排除 id 后无冲突
        when(tableManager.existsByFieldValue("users", "email", "self@x.com", "rec-1"))
                .thenReturn(false);
        Map<String, Object> data = new HashMap<>();
        data.put("email", "self@x.com");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, "rec-1");

        assertThat(out).containsEntry("email", "self@x.com");
    }

    @Test
    void noConstraints_returnsDataUnchanged() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("note", "text", false, "备注", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("note", "hello");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("note", "hello");
    }
}
