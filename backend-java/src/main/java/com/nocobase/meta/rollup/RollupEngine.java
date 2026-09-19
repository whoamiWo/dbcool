package com.nocobase.meta.rollup;

import java.util.*;

/**
 * Rollup / Lookup / Count 关联聚合引擎。
 *
 * <p>复用 RelationResolver/InverseRelationManager 获取关联记录，
 * 然后批量聚合(SUM/COUNT/AVG/MIN/MAX)。
 */
public class RollupEngine {

    public static List<Map<String, Object>> aggregate(
            List<Map<String, Object>> relatedRecords,
            String agg,
            String targetField) {
        if (relatedRecords == null || relatedRecords.isEmpty()) return List.of();
        return switch (agg.toUpperCase()) {
            case "SUM" -> List.of(Map.of("value", sum(relatedRecords, targetField)));
            case "COUNT" -> List.of(Map.of("value", (long) relatedRecords.size()));
            case "AVG" -> List.of(Map.of("value", avg(relatedRecords, targetField)));
            case "MIN" -> List.of(Map.of("value", min(relatedRecords, targetField)));
            case "MAX" -> List.of(Map.of("value", max(relatedRecords, targetField)));
            default -> List.of(Map.of("value", null));
        };
    }

    /** Lookup:取关联记录的 targetField 值（取第一条非空）。 */
    public static Object lookup(List<Map<String, Object>> relatedRecords, String targetField) {
        if (relatedRecords == null || relatedRecords.isEmpty()) return null;
        for (Map<String, Object> r : relatedRecords) {
            Object v = r.get(targetField);
            if (v != null) return v;
        }
        return null;
    }

    /** 便捷：直接返回聚合标量值。 */
    public static Object aggregateValue(List<Map<String, Object>> relatedRecords, String agg, String targetField) {
        List<Map<String, Object>> result = aggregate(relatedRecords, agg, targetField);
        if (result.isEmpty()) return null;
        return result.get(0).get("value");
    }

    private static Double sum(List<Map<String, Object>> records, String field) {
        double s = 0;
        for (Map<String, Object> r : records) {
            Object v = r.get(field);
            if (v instanceof Number n) s += n.doubleValue();
        }
        return s;
    }

    private static Double avg(List<Map<String, Object>> records, String field) {
        double s = 0; int c = 0;
        for (Map<String, Object> r : records) {
            Object v = r.get(field);
            if (v instanceof Number n) { s += n.doubleValue(); c++; }
        }
        return c == 0 ? null : s / c;
    }

    private static Double min(List<Map<String, Object>> records, String field) {
        Double min = null;
        for (Map<String, Object> r : records) {
            Object v = r.get(field);
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (min == null || d < min) min = d;
            }
        }
        return min;
    }

    private static Double max(List<Map<String, Object>> records, String field) {
        Double max = null;
        for (Map<String, Object> r : records) {
            Object v = r.get(field);
            if (v instanceof Number n) {
                double d = n.doubleValue();
                if (max == null || d > max) max = d;
            }
        }
        return max;
    }
}