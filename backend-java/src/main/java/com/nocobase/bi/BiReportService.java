package com.nocobase.bi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.meta.CollectionService;
import com.nocobase.meta.DynamicTableManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BI 报表服务 — 数据透视 + 图表 + 仪表盘。
 *
 * <p><b>数据源(Week 44 接真)</b>:聚合通过 {@link CollectionService#aggregate}
 * 下推到物理表 SQL 完成(GROUP BY + 聚合函数),不再把全量记录拉进 Java 内存归并。
 * 记录正文存于 {@code extra} JSONB 列,字段值由 {@code extra->>'field'} 提取。
 *
 * <p><b>缓存</b>:聚合结果写入 Redis(TTL 5 分钟)以支撑看板频繁刷新;
 * 缓存读写异常不阻断主流程(降级为直查)。
 */
@Service
public class BiReportService {

    private static final Logger log = LoggerFactory.getLogger(BiReportService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String CACHE_PREFIX = "bi:agg:";

    private final CollectionService collectionService;
    private final BiReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public BiReportService(CollectionService collectionService,
                           BiReportRepository reportRepository,
                           ObjectMapper objectMapper,
                           StringRedisTemplate redis) {
        this.collectionService = collectionService;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    // ============================================================
    //  报表定义持久化(BI 页可保存/加载透视配置)
    // ============================================================

    /** 按 collection 列出已保存报表定义。 */
    public List<Map<String, Object>> listReports(String collectionName, String tenantId) {
        return reportRepository.findByTenantIdAndCollectionName(tenantId, collectionName)
                .stream().map(this::toReportDto).toList();
    }

    /** 保存(新建或按 id 更新)报表定义。 */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> saveReport(Map<String, Object> body, String tenantId, UUID userId) {
        String idStr = (String) body.get("id");
        BiReportEntity e;
        if (idStr != null && !idStr.isBlank()) {
            e = reportRepository.findById(UUID.fromString(idStr))
                    .orElseGet(BiReportEntity::new);
        } else {
            e = new BiReportEntity();
            e.setId(UUID.randomUUID());
            e.setTenantId(tenantId);
            e.setCreatedAt(java.time.Instant.now());
        }
        e.setCollectionName((String) body.get("collectionName"));
        e.setName((String) body.getOrDefault("name", "未命名报表"));
        e.setTitle((String) body.get("title"));
        String type = (String) body.getOrDefault("type", "PIVOT");
        try {
            e.setType(BiReportEntity.Type.valueOf(type.toUpperCase()));
        } catch (Exception ex) {
            e.setType(BiReportEntity.Type.PIVOT);
        }
        Object cfg = body.get("config");
        e.setConfigJson(cfg == null ? "{}" : writeJson(cfg));
        e.setCreatedBy(userId);
        e.setUpdatedBy(userId);
        e.setUpdatedAt(java.time.Instant.now());
        return toReportDto(reportRepository.save(e));
    }

    private Map<String, Object> toReportDto(BiReportEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId() == null ? null : e.getId().toString());
        m.put("collectionName", e.getCollectionName());
        m.put("name", e.getName());
        m.put("title", e.getTitle());
        m.put("type", e.getType() == null ? "PIVOT" : e.getType().name());
        m.put("config", parseJson(e.getConfigJson()));
        return m;
    }

    private String writeJson(Object cfg) {
        try { return objectMapper.writeValueAsString(cfg); }
        catch (Exception ex) { return "{}"; }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); }
        catch (Exception ex) { return Map.of(); }
    }

    /**
     * 执行数据透视查询(SQL 下推聚合 + 内存组装矩阵)。
     *
     * @param rows    行维度字段(顺序敏感)
     * @param columns 列维度字段(可为空)
     * @param values  度量:[{field, agg}] — agg ∈ SUM/COUNT/AVG/MIN/MAX
     * @param filters 过滤规则(已由 Controller 转为 FilterRule)
     */
    public Map<String, Object> executePivot(String collectionName, String tenantId,
                                            List<String> rows,
                                            List<String> columns,
                                            List<Map<String, String>> values,
                                            List<CollectionService.FilterRule> filters) {
        log.info("[BI] pivot: collection={}, rows={}, columns={}", collectionName, rows, columns);

        List<String> safeRows = orEmpty(rows);
        List<String> safeCols = orEmpty(columns);
        List<Map<String, String>> measures = values == null ? List.of() : values;

        // 行维度 + 列维度 共同构成 GROUP BY
        List<String> groupBy = new ArrayList<>(safeRows);
        groupBy.addAll(safeCols);

        List<DynamicTableManager.AggSpec> aggSpecs = measures.stream()
                .filter(m -> m != null && m.get("field") != null)
                .map(m -> new DynamicTableManager.AggSpec(
                        m.get("field"),
                        m.getOrDefault("agg", "SUM"),
                        m.get("field")))
                .toList();

        List<Map<String, Object>> aggRows = runAggregate(
                collectionName, tenantId, groupBy, aggSpecs, filters, "pivot");

        // 组装透视矩阵(SQL 已按 groupBy 聚合,每个 (row,col) 组合至多一行)
        Set<String> rowKeys = new LinkedHashSet<>();
        Set<String> colKeys = new LinkedHashSet<>();
        Map<String, Map<String, Map<String, Double>>> matrix = new LinkedHashMap<>();

        for (Map<String, Object> r : aggRows) {
            String rk = joinKey(r, safeRows);
            String ck = joinKey(r, safeCols);
            rowKeys.add(rk);
            colKeys.add(ck);
            for (Map<String, String> m : measures) {
                String field = m.get("field");
                if (field == null) continue;
                matrix.computeIfAbsent(rk, k -> new LinkedHashMap<>())
                      .computeIfAbsent(ck, k -> new LinkedHashMap<>())
                      .put(field, toDouble(r.get(field)));
            }
        }

        List<String> rowList = new ArrayList<>(rowKeys);
        List<String> colList = new ArrayList<>(colKeys);

        List<Map<String, Object>> data = new ArrayList<>();
        for (String rk : rowList) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("row", rk);
            for (String ck : colList) {
                Map<String, Double> cell = matrix
                        .getOrDefault(rk, Map.of()).get(ck);
                if (cell == null) continue;
                for (Map.Entry<String, Double> e : cell.entrySet()) {
                    row.put(ck + "|" + e.getKey(), e.getValue());
                }
            }
            data.add(row);
        }

        return Map.of(
                "rows", rowList,
                "columns", colList,
                "values", measures.stream().map(m -> m.get("field")).collect(Collectors.toList()),
                "data", data,
                "totalRows", rowList.size(),
                "totalCols", colList.size()
        );
    }

    /**
     * 执行图表数据查询(下推聚合)。
     *
     * @param xField      X 轴分类字段
     * @param yField      Y 轴度量字段
     * @param seriesField 系列字段(可为空 → 单系列"默认")
     */
    public Map<String, Object> executeChart(String collectionName, String tenantId,
                                            String chartType,
                                            String xField, String yField, String seriesField,
                                            String agg,
                                            List<CollectionService.FilterRule> filters) {
        log.info("[BI] chart: collection={}, type={}, x={}, y={}",
                collectionName, chartType, xField, yField);

        if (xField == null || yField == null) {
            return Map.of("chartType", chartType == null ? "bar" : chartType,
                    "categories", List.of(), "series", List.of(), "total", 0);
        }

        List<String> groupBy = new ArrayList<>();
        groupBy.add(xField);
        if (seriesField != null && !seriesField.isBlank()) groupBy.add(seriesField);

        List<DynamicTableManager.AggSpec> aggSpecs = List.of(
                new DynamicTableManager.AggSpec(yField, agg == null ? "SUM" : agg, yField));

        List<Map<String, Object>> aggRows = runAggregate(
                collectionName, tenantId, groupBy, aggSpecs, filters, "chart");

        // x 值保序 + 系列名去重
        Set<String> categories = new LinkedHashSet<>();
        Set<String> seriesNames = new LinkedHashSet<>();
        Map<String, Map<String, Double>> cell = new LinkedHashMap<>(); // x -> series -> value

        for (Map<String, Object> r : aggRows) {
            String x = String.valueOf(r.getOrDefault(xField, ""));
            String s = (seriesField != null && !seriesField.isBlank())
                    ? String.valueOf(r.getOrDefault(seriesField, "默认"))
                    : "默认";
            categories.add(x);
            seriesNames.add(s);
            cell.computeIfAbsent(x, k -> new LinkedHashMap<>())
                .put(s, toDouble(r.get(yField)));
        }

        List<String> catList = new ArrayList<>(categories);
        List<Map<String, Object>> series = new ArrayList<>();
        for (String s : seriesNames) {
            List<Double> vals = new ArrayList<>();
            for (String c : catList) {
                vals.add(cell.getOrDefault(c, Map.of()).getOrDefault(s, 0.0));
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", s);
            one.put("data", vals);
            series.add(one);
        }

        return Map.of(
                "chartType", chartType == null ? "bar" : chartType,
                "categories", catList,
                "series", series,
                "total", aggRows.size()
        );
    }

    // ============================================================
    //  聚合执行 + 缓存
    // ============================================================

    /** 走 CollectionService 下推聚合,带 Redis 缓存(异常降级为直查)。 */
    private List<Map<String, Object>> runAggregate(String collectionName, String tenantId,
                                                   List<String> groupBy,
                                                   List<DynamicTableManager.AggSpec> aggSpecs,
                                                   List<CollectionService.FilterRule> filters,
                                                   String kind) {
        if (aggSpecs.isEmpty()) return List.of();
        String key = CACHE_PREFIX + tenantId + ":" + collectionName + ":" + kind + ":"
                + (groupBy == null ? "" : String.join(",", groupBy)) + ":"
                + aggSpecs.stream().map(a -> a.agg() + "(" + a.field() + ")")
                          .collect(Collectors.joining(",")) + ":"
                + (filters == null ? "" : filters.size());

        // 读缓存
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<Map<String, Object>>>() {});
            }
        } catch (Exception e) {
            log.debug("[BI] cache read skipped: {}", e.getMessage());
        }

        List<Map<String, Object>> result =
                collectionService.aggregate(collectionName, tenantId, groupBy, aggSpecs, filters);

        // 写缓存
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(result), CACHE_TTL);
        } catch (Exception e) {
            log.debug("[BI] cache write skipped: {}", e.getMessage());
        }
        return result;
    }

    // ============================================================
    //  工具
    // ============================================================

    private static List<String> orEmpty(List<String> v) {
        return v == null ? List.of() : v;
    }

    /** 多字段组合键(用 | 连接,与既有透视输出格式一致)。 */
    private static String joinKey(Map<String, Object> record, List<String> fields) {
        if (fields.isEmpty()) return "";
        return fields.stream()
                .map(f -> String.valueOf(record.getOrDefault(f, "")))
                .collect(Collectors.joining("|"));
    }

    private static double toDouble(Object obj) {
        if (obj == null) return 0.0;
        if (obj instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(obj.toString()); } catch (Exception e) { return 0.0; }
    }
}