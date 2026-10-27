package com.nocobase.bi.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportExcelExporterTest {

    private final ReportExcelExporter exporter = new ReportExcelExporter();

    // ============================================================
    //  1. 透视表 Excel 导出
    // ============================================================

    @Test
    void exportPivot_generatesValidXlsx() {
        Map<String, Object> pivotResult = Map.of(
                "rows", List.of("category"),
                "columns", List.of("region"),
                "values", List.of("sales"),
                "data", List.of(
                        Map.of("row", "Electronics", "region|sales", 1200.5),
                        Map.of("row", "Furniture", "region|sales", 850.0)
                )
        );

        byte[] bytes = exporter.exportPivot(pivotResult, "Sales Pivot");

        assertThat(bytes).isNotEmpty().hasSizeGreaterThan(100);
        // 简单验证是 zip 格式 (.xlsx 本质是 zip)
        assertThat(bytes[0]).isEqualTo((byte) 0x50);
        assertThat(bytes[1]).isEqualTo((byte) 0x4B);
    }

    // ============================================================
    //  2. 扁平列表 Excel 导出
    // ============================================================

    @Test
    void exportFlat_generatesValidXlsx() {
        List<Map<String, Object>> records = List.of(
                Map.of("name", "Alice", "age", 30, "score", 95.5),
                Map.of("name", "Bob", "age", 25, "score", 88.0)
        );

        byte[] bytes = exporter.exportFlat(records, "Users");

        assertThat(bytes).isNotEmpty().hasSizeGreaterThan(100);
        assertThat(bytes[0]).isEqualTo((byte) 0x50);
        assertThat(bytes[1]).isEqualTo((byte) 0x4B);
    }
}
