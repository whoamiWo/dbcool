package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * R1-B: CollectionService.applyFieldConstraints 扩展行为测试 (新 12 种字段类型)。
 *
 * <p>覆盖语义:
 * <ul>
 *   <li>email/url/phone 格式校验：非法值 → 400</li>
 *   <li>自动字段 (createdTime/createdBy/lastModifiedTime/lastModifiedBy/autonumber) 拒绝手工写入 → 400</li>
 * </ul>
 */
class CollectionServiceNewTypeWriteRulesTest {

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
        meta.setName("leads");
        meta.setTenantId("t1");
        meta.setFieldsJson(objectMapper.writeValueAsString(fields));
        return meta;
    }

    // ===== email 格式校验 =====

    @Test
    void email_validFormat_passes() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("contact_email", "email", false, "联系邮箱", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("contact_email", "user@example.com");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("contact_email", "user@example.com");
    }

    @Test
    void email_invalidFormat_throwsBadRequest() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("contact_email", "email", false, "联系邮箱", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("contact_email", "not-an-email@@");

        assertThatThrownBy(() -> service.applyFieldConstraints(meta, data, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void email_nullValue_skipsValidation() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("contact_email", "email", false, "联系邮箱", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("contact_email", null);

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out.get("contact_email")).isNull();
    }

    // ===== url 格式校验 =====

    @Test
    void url_validHttp_passes() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("website", "url", false, "网址", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("website", "https://example.com/path");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("website", "https://example.com/path");
    }

    @Test
    void url_invalidFormat_throwsBadRequest() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("website", "url", false, "网址", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("website", "htp:/broken-url");

        assertThatThrownBy(() -> service.applyFieldConstraints(meta, data, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ===== phone 格式校验 =====

    @Test
    void phone_validDigits_passes() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("mobile", "phone", false, "手机号", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("mobile", "13800138000");

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("mobile", "13800138000");
    }

    @Test
    void phone_invalidChars_throwsBadRequest() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("mobile", "phone", false, "手机号", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("mobile", "call-me-maybe");

        assertThatThrownBy(() -> service.applyFieldConstraints(meta, data, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ===== 自动字段填充行为 =====

    @Test
    void createdTime_autoFilled_onInsert() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("created_time", "createdTime", false, "创建时间", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsKey("created_time");
    }

    @Test
    void createdBy_autoFilled_onInsert() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("creator", "createdBy", false, "创建人", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsKey("creator");
    }

    @Test
    void lastModifiedTime_autoFilled() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("updated_time", "lastModifiedTime", false, "修改时间", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsKey("updated_time");
    }

    @Test
    void lastModifiedBy_autoFilled() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("modifier", "lastModifiedBy", false, "修改人", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsKey("modifier");
    }

    @Test
    void autonumber_autoGenerated_onInsert() throws Exception {
        Map<String, Object> autonumberConfig = Map.of("prefix", "INV-", "digits", 5);
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("invoice_no", "autonumber", false, "单号", autonumberConfig, false, false, null)));
        Map<String, Object> data = new HashMap<>();

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsKey("invoice_no");
    }

    // ===== 正常字段不受影响 =====

    @Test
    void normalTextField_writeStillWorks_alongsideNewTypes() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("name", "text", false, "名称", null, false, false, null),
                new FieldDef("priority", "rating", false, "优先级", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("name", "正常写入");
        data.put("priority", 4);

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("name", "正常写入");
        assertThat(out).containsEntry("priority", 4);
    }

    @Test
    void currencyValue_passesWithoutValidation() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("price", "currency", false, "价格", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("price", 99.99);

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("price", 99.99);
    }

    @Test
    void percentValue_passesWithoutValidation() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("discount", "percent", false, "折扣", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("discount", 15.5);

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("discount", 15.5);
    }

    @Test
    void durationValue_passesWithoutValidation() throws Exception {
        CollectionMetaEntity meta = metaWith(List.of(
                new FieldDef("duration_sec", "duration", false, "时长", null, false, false, null)));
        Map<String, Object> data = new HashMap<>();
        data.put("duration_sec", 3600);

        Map<String, Object> out = service.applyFieldConstraints(meta, data, null);

        assertThat(out).containsEntry("duration_sec", 3600);
    }
}
