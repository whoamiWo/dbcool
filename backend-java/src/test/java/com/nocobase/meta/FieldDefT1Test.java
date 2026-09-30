package com.nocobase.meta;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1: 字段类型扩展后端单元测试 — 验证新增 12 种类型的合法性。
 */
class FieldDefT1Test {

    @Test
    @DisplayName("T1 新增字段类型应全部合法")
    void t1NewFieldTypesAreValid() {
        // 可编辑类型 (7)
        assertTrue(FieldDef.isValidType("email"), "email should be valid");
        assertTrue(FieldDef.isValidType("url"), "url should be valid");
        assertTrue(FieldDef.isValidType("phone"), "phone should be valid");
        assertTrue(FieldDef.isValidType("currency"), "currency should be valid");
        assertTrue(FieldDef.isValidType("percent"), "percent should be valid");
        assertTrue(FieldDef.isValidType("rating"), "rating should be valid");
        assertTrue(FieldDef.isValidType("duration"), "duration should be valid");

        // 自动类型 (5)
        assertTrue(FieldDef.isValidType("createdTime"), "createdTime should be valid");
        assertTrue(FieldDef.isValidType("lastModifiedTime"), "lastModifiedTime should be valid");
        assertTrue(FieldDef.isValidType("createdBy"), "createdBy should be valid");
        assertTrue(FieldDef.isValidType("lastModifiedBy"), "lastModifiedBy should be valid");
        assertTrue(FieldDef.isValidType("autonumber"), "autonumber should be valid");
    }

    @Test
    @DisplayName("原有 13 种类型仍应合法（回归检查）")
    void original13TypesStillValid() {
        // 基础
        assertTrue(FieldDef.isValidType("text"));
        assertTrue(FieldDef.isValidType("number"));
        assertTrue(FieldDef.isValidType("boolean"));
        assertTrue(FieldDef.isValidType("date"));
        assertTrue(FieldDef.isValidType("datetime"));
        // 枚举
        assertTrue(FieldDef.isValidType("select"));
        assertTrue(FieldDef.isValidType("multiSelect"));
        // 关联
        assertTrue(FieldDef.isValidType("belongsTo"));
        assertTrue(FieldDef.isValidType("hasMany"));
        // 派生
        assertTrue(FieldDef.isValidType("formula"));
        assertTrue(FieldDef.isValidType("rollup"));
        assertTrue(FieldDef.isValidType("lookup"));
        // 文件
        assertTrue(FieldDef.isValidType("attachment"));
    }
}