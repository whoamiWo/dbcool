package com.nocobase.bi;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.meta.CollectionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BI 报表 REST API — 数据透视 + 图表(数据源已下推至物理表 SQL 聚合)。
 *
 * <p>请求体 filters 为数组:[{"field":"status","op":"eq","value":"PUBLISHED"}],
 * 与 Collection 引擎的 FilterRule 语义一致(eq/neq/contains/gt/lt/empty/notEmpty)。
 */
@RestController
@RequestMapping("/api/bi")
public class BiReportController {

    private final BiReportService reportService;

    public BiReportController(BiReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * 数据透视:body = {collection, rows[], columns[], values[{field,agg}], filters[]}
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/pivot")
    public ResponseEntity<Map<String, Object>> executePivot(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String collection = (String) body.get("collection");
        if (collection == null || collection.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("code", 400, "message", "collection 必填"));
        }
        List<String> rows = castStrList(body.get("rows"));
        List<String> columns = castStrList(body.get("columns"));
        List<Map<String, String>> values = castMeasureList(body.get("values"));
        List<CollectionService.FilterRule> filters = parseFilters(body.get("filters"));

        Map<String, Object> result = reportService.executePivot(
                collection, user.tenantId(), rows, columns, values, filters);

        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", result));
    }

    /**
     * 图表数据:body = {collection, chartType, xField, yField, seriesField, agg, filters[]}
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/chart")
    public ResponseEntity<Map<String, Object>> executeChart(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String collection = (String) body.get("collection");
        if (collection == null || collection.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("code", 400, "message", "collection 必填"));
        }
        String chartType = (String) body.getOrDefault("chartType", "bar");
        String xField = (String) body.get("xField");
        String yField = (String) body.get("yField");
        String seriesField = (String) body.get("seriesField");
        String agg = (String) body.getOrDefault("agg", "SUM");
        List<CollectionService.FilterRule> filters = parseFilters(body.get("filters"));

        Map<String, Object> result = reportService.executeChart(
                collection, user.tenantId(), chartType, xField, yField, seriesField, agg, filters);

        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", result));
    }

    /**
     * 已保存报表列表(按 collection)。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/reports")
    public ResponseEntity<Map<String, Object>> listReports(
            @RequestParam String collectionName,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        List<Map<String, Object>> reports = reportService.listReports(collectionName, user.tenantId());
        return ResponseEntity.ok(Map.of("code", 0, "message", "success", "data", reports));
    }

    /**
     * 保存报表定义。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/reports")
    public ResponseEntity<Map<String, Object>> saveReport(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Map<String, Object> saved = reportService.saveReport(body, user.tenantId(), user.userId());
        return ResponseEntity.ok(Map.of("code", 0, "message", "saved", "data", saved));
    }

    /**
     * 导出报表 (GET /api/bi/export?collection=...&type=pivot&format=xlsx)。
     *
     * <p>pivot 类型: body 参数直接执行透视查询后导出;
     * flat 类型: 导出明细列表(可带 filters)。
     */
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportReport(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        String collection = (String) body.get("collection");
        if (collection == null || collection.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String format = (String) body.getOrDefault("format", "xlsx");
        String type = (String) body.getOrDefault("type", "pivot");
        List<CollectionService.FilterRule> filters = parseFilters(body.get("filters"));

        boolean isPdf = "pdf".equalsIgnoreCase(format);
        String filename = URLEncoder.encode(
                (String) body.getOrDefault("name", "report"), StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        if ("flat".equalsIgnoreCase(type)) {
            List<Map<String, Object>> records = reportService
                    .listFlatRecords(collection, user.tenantId(), filters);
            byte[] bytes = isPdf
                    ? reportService.exportFlatToPdf(records, (String) body.getOrDefault("name", "报表"))
                    : reportService.exportFlatToExcel(records, (String) body.getOrDefault("name", "报表"));
            return buildFileResponse(bytes, isPdf, filename);
        }

        // pivot: rows/columns/values + filters
        List<String> rows = castStrList(body.get("rows"));
        List<String> columns = castStrList(body.get("columns"));
        List<Map<String, String>> values = castMeasureList(body.get("values"));
        Map<String, Object> pivotResult = reportService.executePivot(
                collection, user.tenantId(), rows, columns, values, filters);

        byte[] bytes = isPdf
                ? reportService.exportPivotToPdf(pivotResult, (String) body.getOrDefault("name", "报表"))
                : reportService.exportPivotToExcel(pivotResult, (String) body.getOrDefault("name", "报表"));
        return buildFileResponse(bytes, isPdf, filename);
    }

    /**
     * 按报表 ID 导出 (GET /api/bi/reports/{id}/export?format=xlsx|pdf)。
     *
     * <p>报表定义已保存，直接按其 config 重放查询后导出；复用已有导出管线。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/reports/{id}/export")
    public ResponseEntity<byte[]> exportReportById(
            @PathVariable("id") String id,
            @RequestParam(value = "format", defaultValue = "xlsx") String format,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        java.util.UUID reportId;
        try {
            reportId = java.util.UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }

        boolean isPdf = "pdf".equalsIgnoreCase(format);
        byte[] bytes = isPdf
                ? reportService.exportToPdf(reportId, user.tenantId())
                : reportService.exportToExcel(reportId, user.tenantId());

        String filename = URLEncoder.encode("report-" + id, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");
        return buildFileResponse(bytes, isPdf, filename);
    }

    private ResponseEntity<byte[]> buildFileResponse(byte[] bytes, boolean isPdf, String encodedName) {
        String ext = isPdf ? "pdf" : "xlsx";
        String mime = isPdf ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName + "." + ext)
                .contentType(MediaType.parseMediaType(mime))
                .body(bytes);
    }

    // ============================================================
    //  参数解析
    // ============================================================

    /** filters:[{field, op, value}] → FilterRule 列表(与 Collection 引擎语义一致)。 */
    private List<CollectionService.FilterRule> parseFilters(Object raw) {
        List<CollectionService.FilterRule> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String field = (String) m.get("field");
            if (field == null || field.isBlank()) continue;
            out.add(new CollectionService.FilterRule(
                    field, (String) m.get("op"), m.get("value")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> castStrList(Object raw) {
        return raw instanceof List<?> l ? (List<String>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> castMeasureList(Object raw) {
        return raw instanceof List<?> l ? (List<Map<String, String>>) l : List.of();
    }
}