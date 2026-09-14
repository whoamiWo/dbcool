package com.nocobase.meta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * FieldDef 类型白名单测试(Week 41 D1.1).
 */
class FieldDefTypeValidationTest {

    @Test
    void attachment_isValid() {
        assertThat(FieldDef.isValidType("attachment")).isTrue();
        new FieldDef("file_url", "attachment", false, "附件", Map.of());
    }

    @Test
    void datetime_isValid() {
        assertThat(FieldDef.isValidType("datetime")).isTrue();
        new FieldDef("created_at_custom", "datetime", false, "时间", Map.of());
    }

    @Test
    void allBaselineTypes_stillValid() {
        for (String t : new String[]{
                "text", "number", "boolean", "date",
                "select", "multiSelect",
                "belongsTo", "hasMany", "formula"
        }) {
            assertThat(FieldDef.isValidType(t)).as("类型 %s 应继续有效", t).isTrue();
        }
    }

    @Test
    void unknownType_rejected() {
        assertThat(FieldDef.isValidType("emoji")).isFalse();
        assertThatThrownBy(() -> new FieldDef("x", "emoji", false, "x", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalSupportedTypes_count() {
        long count = java.util.Arrays.stream(new String[]{
                "text", "number", "boolean", "date", "datetime",
                "select", "multiSelect", "attachment",
                "belongsTo", "hasMany", "formula"
        }).filter(FieldDef::isValidType).count();
        assertThat(count).isEqualTo(11);
    }
}
