package com.nocobase.bi;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import com.nocobase.meta.CollectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
    @PostMapping("/reports")
    public ResponseEntity<Map<String, Object>> saveReport(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        Map<String, Object> saved = reportService.saveReport(body, user.tenantId(), user.userId());
        return ResponseEntity.ok(Map.of("code", 0, "message", "saved", "data", saved));
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