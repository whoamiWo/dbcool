package com.nocobase.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FieldDefTest {

    @Test
    void valid_field_types_accepted() {
        for (String type : new String[]{"text", "number", "boolean", "date", "select"}) {
            FieldDef f = new FieldDef("f_" + type, type, false, null, null);
            assertEquals(type, f.type());
        }
    }

    @Test
    void invalid_type_rejected() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> new FieldDef("f1", "unknown_type", false, null, null)
        );
        assertTrue(e.getMessage().contains("不支持的字段类型"));
    }

    @Test
    void blank_name_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldDef("", "text", false, null, null));
    }

    @Test
    void null_type_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FieldDef("f1", null, false, null, null));
    }

    @Test
    void isValidType_checks_known_types() {
        assertTrue(FieldDef.isValidType("text"));
        assertTrue(FieldDef.isValidType("belongsTo"));
        assertTrue(FieldDef.isValidType("hasMany"));
        assertTrue(FieldDef.isValidType("formula"));
        org.junit.jupiter.api.Assertions.assertFalse(FieldDef.isValidType("foobar"));
    }
}
