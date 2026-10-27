package com.nocobase.bi;

import com.nocobase.auth.JwtAuthFilter.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PHASE105: BiReportController 测试 (pivot/chart/list/save)。 */
class BiReportControllerTest {

    private BiReportService reportService;
    private BiReportController controller;

    private static final AuthenticatedUser USER =
            new AuthenticatedUser(UUID.randomUUID(), "alice", "tenant_default");

    @BeforeEach
    void setUp() {
        reportService = mock(BiReportService.class);
        controller = new BiReportController(reportService);
    }

    @Test
    void executePivot_missingCollection_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.executePivot(Map.of("rows", List.of()), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).isEqualTo("collection 必填");
    }

    @Test
    void executePivot_success_returnsResult() {
        when(reportService.executePivot(eq("users"), eq(USER.tenantId()),
                any(List.class), any(List.class), any(List.class), any(List.class)))
                .thenReturn(Map.of("data", "pivot"));

        ResponseEntity<Map<String, Object>> resp =
                controller.executePivot(Map.of("collection", "users", "rows", List.of("status")), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
        verify(reportService).executePivot(eq("users"), eq(USER.tenantId()),
                any(List.class), any(List.class), any(List.class), any(List.class));
    }

    @Test
    void executeChart_missingCollection_returns400() {
        ResponseEntity<Map<String, Object>> resp =
                controller.executeChart(Map.of("xField", "x", "yField", "y"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("message")).isEqualTo("collection 必填");
    }

    @Test
    void executeChart_success_returnsResult() {
        when(reportService.executeChart(eq("orders"), eq(USER.tenantId()),
                eq("bar"), eq("category"), eq("amount"), eq(null), eq("SUM"), any(List.class)))
                .thenReturn(Map.of("data", "chart"));

        ResponseEntity<Map<String, Object>> resp =
                controller.executeChart(Map.of("collection", "orders", "chartType", "bar",
                        "xField", "category", "yField", "amount"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    @Test
    void listReports_returnsList() {
        when(reportService.listReports("products", USER.tenantId()))
                .thenReturn(List.of(Map.of("name", "r1")));

        ResponseEntity<Map<String, Object>> resp =
                controller.listReports("products", USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((List<?>)resp.getBody().get("data")).size()).isEqualTo(1);
    }

    @Test
    void saveReport_returnsSaved() {
        Map<String, Object> body = Map.of("name", "r1", "definition", Map.of());
        when(reportService.saveReport(body, USER.tenantId(), USER.userId()))
                .thenReturn(Map.of("id", 1, "name", "r1"));

        ResponseEntity<Map<String, Object>> resp = controller.saveReport(body, USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("code")).isEqualTo(0);
    }

    // ============================================================
    //  导出端点 (Wave 4 C1)
    // ============================================================

    @Test
    void exportReport_missingCollection_returns400() {
        ResponseEntity<byte[]> resp = controller.exportReport(Map.of("format", "xlsx"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void exportReport_pivotExcel_returnsAttachment() {
        when(reportService.executePivot(eq("orders"), eq(USER.tenantId()),
                any(List.class), any(List.class), any(List.class), any(List.class)))
                .thenReturn(Map.of("rows", List.of("dept"), "data", List.of()));
        when(reportService.exportPivotToExcel(any(), any())).thenReturn(new byte[]{1, 2, 3});

        ResponseEntity<byte[]> resp = controller.exportReport(
                Map.of("collection", "orders", "type", "pivot", "format", "xlsx", "name", "月度报表"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(3);
        assertThat(resp.getHeaders().getFirst("Content-Disposition")).contains("attachment");
        assertThat(resp.getHeaders().getContentType().toString()).contains("spreadsheetml");
    }

    @Test
    void exportReport_pivotPdf_returnsPdfContentType() {
        when(reportService.executePivot(eq("orders"), eq(USER.tenantId()),
                any(List.class), any(List.class), any(List.class), any(List.class)))
                .thenReturn(Map.of("rows", List.of("dept"), "data", List.of()));
        when(reportService.exportPivotToPdf(any(), any())).thenReturn(new byte[]{9, 9});

        ResponseEntity<byte[]> resp = controller.exportReport(
                Map.of("collection", "orders", "type", "pivot", "format", "pdf"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).contains("application/pdf");
    }

    @Test
    void exportReport_flatExcel_usesListRecords() {
        when(reportService.listFlatRecords(eq("orders"), eq(USER.tenantId()), any(List.class)))
                .thenReturn(List.of(Map.of("id", 1)));
        when(reportService.exportFlatToExcel(any(List.class), any())).thenReturn(new byte[]{7});

        ResponseEntity<byte[]> resp = controller.exportReport(
                Map.of("collection", "orders", "type", "flat", "format", "xlsx"), USER);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        verify(reportService).listFlatRecords(eq("orders"), eq(USER.tenantId()), any(List.class));
    }
}
