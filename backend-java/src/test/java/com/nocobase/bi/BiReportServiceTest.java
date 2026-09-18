package com.nocobase.bi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nocobase.meta.CollectionService;
import com.nocobase.meta.DynamicTableManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class BiReportServiceTest {

    private CollectionService collectionService;
    private BiReportRepository reportRepo;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private BiReportService service;

    @BeforeEach
    void setUp() {
        collectionService = mock(CollectionService.class);
        reportRepo = mock(BiReportRepository.class);
        valueOps = mock(ValueOperations.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        objectMapper = new ObjectMapper();
        service = new BiReportService(collectionService, reportRepo, objectMapper, redis);
    }

    private String tid = "tenant_default";

    // ============================================================
    //  1. listReports
    // ============================================================

    @Test
    void listReports_returnsSavedReports() {
        BiReportEntity e = new BiReportEntity();
        e.setId(UUID.randomUUID());
        e.setCollectionName("posts");
        e.setName("Sales Pivot");
        e.setTitle("销售透视");
        e.setType(BiReportEntity.Type.PIVOT);
        e.setConfigJson("{}");
        when(reportRepo.findByTenantIdAndCollectionName(tid, "posts"))
                .thenReturn(List.of(e));

        List<Map<String, Object>> result = service.listReports("posts", tid);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("name")).isEqualTo("Sales Pivot");
    }

    // ============================================================
    //  2. saveReport (new)
    // ============================================================

    @Test
    void saveReport_newReport_persistsWithDefaults() {
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = Map.of(
                "collectionName", "orders",
                "name", "Order Pivot",
                "type", "PIVOT",
                "config", Map.of("rows", List.of("status"))
        );
        Map<String, Object> result = service.saveReport(body, tid, UUID.randomUUID());
        assertThat(result.get("name")).isEqualTo("Order Pivot");
        assertThat(result.get("type")).isEqualTo("PIVOT");
        assertThat(result.get("collectionName")).isEqualTo("orders");
    }

    @Test
    void saveReport_updateExisting_updatesFields() {
        UUID id = UUID.randomUUID();
        BiReportEntity existing = new BiReportEntity();
        existing.setId(id);
        existing.setTenantId(tid);
        existing.setCollectionName("orders");
        existing.setName("Old Name");
        existing.setType(BiReportEntity.Type.PIVOT);
        existing.setConfigJson("{}");
        when(reportRepo.findById(id)).thenReturn(java.util.Optional.of(existing));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> body = Map.of(
                "id", id.toString(),
                "name", "Updated Name",
                "type", "CHART"
        );
        Map<String, Object> result = service.saveReport(body, tid, UUID.randomUUID());
        assertThat(result.get("name")).isEqualTo("Updated Name");
        assertThat(result.get("type")).isEqualTo("CHART");
    }

    // ============================================================
    //  3. executePivot
    // ============================================================

    @Test
    void executePivot_basic_returnsMatrix() {
        when(collectionService.aggregate(anyString(), anyString(), any(), any(), any()))
                .thenReturn(List.of(
                        Map.of("department", "Eng", "status", "open", "amount", 100.0),
                        Map.of("department", "Eng", "status", "closed", "amount", 200.0),
                        Map.of("department", "Sales", "status", "open", "amount", 150.0)
                ));

        Map<String, Object> result = service.executePivot(
                "tickets", tid,
                List.of("department"),
                List.of("status"),
                List.of(Map.of("field", "amount", "agg", "SUM")),
                null
        );
        assertThat(result.get("rows")).asList().containsExactly("Eng", "Sales");
        assertThat(result.get("columns")).asList().containsExactly("open", "closed");
        assertThat(result.get("totalRows")).isEqualTo(2);
        assertThat(result.get("totalCols")).isEqualTo(2);
    }

    @Test
    void executePivot_emptyDimensions_returnsEmpty() {
        Map<String, Object> result = service.executePivot(
                "tickets", tid, null, null,
                List.of(Map.of("field", "amount", "agg", "SUM")), null
        );
        assertThat(result.get("rows")).asList().isEmpty();
        assertThat(result.get("columns")).asList().isEmpty();
        assertThat(result.get("data")).asList().isEmpty();
    }

    // ============================================================
    //  4. executeChart
    // ============================================================

    @Test
    void executeChart_basic_returnsSeries() {
        when(collectionService.aggregate(anyString(), anyString(), any(), any(), any()))
                .thenReturn(List.of(
                        Map.of("month", "Jan", "amount", 100.0),
                        Map.of("month", "Feb", "amount", 200.0)
                ));

        Map<String, Object> result = service.executeChart(
                "sales", tid, "bar",
                "month", "amount", null, "SUM", null
        );
        assertThat(result.get("chartType")).isEqualTo("bar");
        assertThat((List<String>) result.get("categories")).containsExactly("Jan", "Feb");
        assertThat(result.get("total")).isEqualTo(2);
    }

    @Test
    void executeChart_withSeries_splitsBySeries() {
        when(collectionService.aggregate(anyString(), anyString(), any(), any(), any()))
                .thenReturn(List.of(
                        Map.of("month", "Jan", "region", "North", "amount", 100.0),
                        Map.of("month", "Jan", "region", "South", "amount", 50.0)
                ));

        Map<String, Object> result = service.executeChart(
                "sales", tid, "bar",
                "month", "amount", "region", "SUM", null
        );
        assertThat(result.get("categories")).asList().containsExactly("Jan");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) result.get("series");
        assertThat(series).hasSize(2);
    }

    // ============================================================
    //  5. executeChart_nullFields_returnsEmpty
    // ============================================================

    @Test
    void executeChart_nullXOrY_returnsEmpty() {
        Map<String, Object> result = service.executeChart(
                "sales", tid, "bar", null, "amount", null, "SUM", null
        );
        assertThat(result.get("categories")).asList().isEmpty();
        assertThat(result.get("series")).asList().isEmpty();
    }

    // ============================================================
    //  6. runAggregate (cache path)
    // ============================================================

    @Test
    void runAggregate_cacheHit_skipsCollectionService() throws Exception {
        String cachedJson = objectMapper.writeValueAsString(
                List.of(Map.of("department", "Eng", "amount", 100.0)));
        when(valueOps.get(anyString())).thenReturn(cachedJson);

        Map<String, Object> result = service.executePivot(
                "tickets", tid,
                List.of("department"), null,
                List.of(Map.of("field", "amount", "agg", "SUM")), null
        );
        // cache hit → collectionService.aggregate should NOT be called
        // but executePivot calls runAggregate internally, we verify via result
        assertThat((List<String>) result.get("rows")).contains("Eng");
    }

    // ============================================================
    //  7. toReportDto config parsing
    // ============================================================

    @Test
    void toReportDto_parsesConfigJson() {
        BiReportEntity e = new BiReportEntity();
        e.setId(UUID.randomUUID());
        e.setCollectionName("posts");
        e.setName("Test");
        e.setType(BiReportEntity.Type.PIVOT);
        e.setConfigJson("{\"rows\":[\"status\"]}");
        when(reportRepo.findByTenantIdAndCollectionName(tid, "posts")).thenReturn(List.of(e));

        Map<String, Object> dto = service.listReports("posts", tid).get(0);
        // listReports delegates to toReportDto; verify config parsed as Map
        assertThat(dto.get("config")).isInstanceOf(Map.class);
    }
}