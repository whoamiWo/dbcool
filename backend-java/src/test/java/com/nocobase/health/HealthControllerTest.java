package com.nocobase.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthControllerTest {

    private DataSource dataSource;
    private Connection connection;
    private ConnectionPoolMonitor poolMonitor;
    private HealthController controller;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        poolMonitor = mock(ConnectionPoolMonitor.class);
        when(dataSource.getConnection()).thenReturn(connection);
        // R09: 默认连接池健康,不影响原有 ready 断言
        when(poolMonitor.isHealthy()).thenReturn(true);
        when(poolMonitor.snapshot()).thenReturn(Map.of("available", true, "active", 1));
        controller = new HealthController(dataSource, poolMonitor);
    }

    @Test
    void health_returnsOkWithVersion() {
        Map<String, Object> resp = controller.health();
        assertThat(resp).containsEntry("status", "ok");
        assertThat(resp).containsKey("timestamp");
    }

    @Test
    void ready_allOk_returns200() throws Exception {
        when(connection.isValid(anyInt())).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.ready();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("status", "ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).containsEntry("database", "ok");
    }

    @Test
    void ready_dbDown_returns503() throws Exception {
        when(connection.isValid(anyInt())).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.ready();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("status", "degraded");
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) body.get("components");
        assertThat(components).containsEntry("database", "down");
    }

    @Test
    void ready_dbConnectionThrows_returns503() throws Exception {
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("refused"));
        controller = new HealthController(dataSource, poolMonitor);

        ResponseEntity<Map<String, Object>> resp = controller.ready();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) ((Map<String, Object>) resp.getBody()).get("components");
        assertThat(components).containsEntry("database", "down");
    }

    @Test
    void ready_poolUnhealthy_returns503() throws Exception {
        when(connection.isValid(anyInt())).thenReturn(true);
        ConnectionPoolMonitor degraded = mock(ConnectionPoolMonitor.class);
        when(degraded.isHealthy()).thenReturn(false);
        when(degraded.snapshot()).thenReturn(Map.of("available", true, "active", 20, "max", 20,
                "threadsAwaitingConnection", 5));
        controller = new HealthController(dataSource, degraded);

        ResponseEntity<Map<String, Object>> resp = controller.ready();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) ((Map<String, Object>) resp.getBody()).get("components");
        assertThat(components).containsEntry("database", "ok");
        assertThat(components).containsEntry("connectionPool", "degraded");
    }

    @Test
    void poolStats_returns200WithSnapshot() {
        ResponseEntity<Map<String, Object>> resp = controller.poolStats();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("available", true);
    }
}
