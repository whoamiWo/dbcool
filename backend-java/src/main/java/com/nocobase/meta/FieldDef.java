package com.nocobase.meta;

import java.util.Map;

/**
 * Collection 字段定义(运行时).
 *
 * <p>字段类型: text / number / boolean / date / select / belongsTo / hasMany / formula
 *
 * <p>基础字段(id / created_at / created_by / updated_at / updated_by)由系统自动加.
 * 用户定义的"动态字段"存到 JSONB 的 extra 字段里.
 */
public record FieldDef(
        String name,
        String type,
        boolean required,
        String label,
        Map<String, Object> options
) {
    public FieldDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field.name 不能为空");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("field.type 不能为空");
        }
        if (!isValidType(type)) {
            throw new IllegalArgumentException("不支持的字段类型: " + type);
        }
    }

    public static boolean isValidType(String type) {
        return switch (type) {
            case "text", "number", "boolean", "date",
                 "select", "multiSelect",
                 "belongsTo", "hasMany", "formula" -> true;
            default -> false;
        };
    }
}
