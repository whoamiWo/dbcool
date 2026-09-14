package com.nocobase.meta;

import java.util.Map;

/**
 * Collection 字段定义(运行时).
 *
 * <p>字段类型(Week 41 D1 扩展):
 * <ul>
 *   <li>基础: text / number / boolean / date / datetime</li>
 *   <li>枚举: select / multiSelect</li>
 *   <li>关联: belongsTo / hasMany</li>
 *   <li>派生: formula(Week 42 与 D4b 表达式引擎一并实现)</li>
 *   <li>文件: attachment(Week 41 D1.1 新增 — 存文件 key 到 JSONB 字段)</li>
 * </ul>
 *
 * <p>基础字段(id / created_at / created_by / updated_at / updated_by)由系统自动加.
 * 用户定义的"动态字段"存到 JSONB 的 extra 字段里.
 *
 * <p>attachment 字段的物理列映射: TEXT(存文件 key 或 JSON 元数据,Week 41 D1)。
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
            // 基础
            case "text", "number", "boolean", "date", "datetime" -> true;
            // 枚举
            case "select", "multiSelect" -> true;
            // 关联
            case "belongsTo", "hasMany" -> true;
            // 派生
            case "formula" -> true;
            // 文件 (Week 41 D1)
            case "attachment" -> true;
            default -> false;
        };
    }
}
