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
    private HealthController controller;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        controller = new HealthController(dataSource);
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
        controller = new HealthController(dataSource);

        ResponseEntity<Map<String, Object>> resp = controller.ready();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) ((Map<String, Object>) resp.getBody()).get("components");
        assertThat(components).containsEntry("database", "down");
    }
}
