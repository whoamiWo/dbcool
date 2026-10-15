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
}
