package com.nocobase.bi.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportPdfExporterTest {

    private final ReportPdfExporter exporter = new ReportPdfExporter();

    // ============================================================
    //  1. 透视表 PDF 导出
    // ============================================================

    @Test
    void exportPivot_generatesValidPdf() {
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

        assertThat(bytes).isNotEmpty().hasSizeGreaterThan(200);
        // PDF 魔数 %PDF-
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }

    // ============================================================
    //  2. 扁平列表 PDF 导出
    // ============================================================

    @Test
    void exportFlat_generatesValidPdf() {
        List<Map<String, Object>> records = List.of(
                Map.of("name", "Alice", "age", 30, "score", 95.5),
                Map.of("name", "Bob", "age", 25, "score", 88.0)
        );

        byte[] bytes = exporter.exportFlat(records, "Users");

        assertThat(bytes).isNotEmpty().hasSizeGreaterThan(200);
        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }
}
