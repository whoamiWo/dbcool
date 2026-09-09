package com.nocobase;

import com.nocobase.health.HealthController;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 健康检查单元测试.
 *
 * <p>不启动 Spring 容器,纯方法测试.
 */
class HealthControllerTest {

    @Test
    void health_should_return_ok() {
        var controller = new HealthController();
        Map<String, Object> result = controller.health();

        assertEquals("ok", result.get("status"));
        assertEquals("nocobase-backend", result.get("service"));
        assertEquals("0.0.1", result.get("version"));
        assertNotNull(result.get("timestamp"));
        assertTrue(result.get("timestamp").toString().contains("T"));
    }
}
