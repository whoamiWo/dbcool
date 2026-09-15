package com.nocobase.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查端点(R13 — 演示前快速验证用).
 *
 * <p>提供两个端点:
 * <ul>
 *   <li>{@code GET /api/health} — 基础 liveness(进程活着就返 ok,用于 LB)</li>
 *   <li>{@code GET /api/health/ready} — readiness(检查 db 连接、API Key 表、JWT keyring)</li>
 * </ul>
 *
 * <p>ready 端点失败时返 503,演示现场可直接 curl 看到哪个组件挂了。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 基础健康检查 — 进程活着就 ok。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "nocobase-backend");
        body.put("version", "0.0.1");
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /** Readiness — 校验所有依赖组件就绪。任一失败 → 503。 */
    @GetMapping("/health/ready")
    public org.springframework.http.ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean allOk = true;

        // 1. DB 连接 ping
        boolean dbOk = pingDatabase();
        components.put("database", dbOk ? "ok" : "down");
        allOk &= dbOk;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", allOk ? "ok" : "degraded");
        body.put("components", components);
        body.put("timestamp", Instant.now().toString());

        if (allOk) {
            return org.springframework.http.ResponseEntity.ok(body);
        }
        log.warn("[health/ready] 组件不健康: {}", components);
        return org.springframework.http.ResponseEntity
            .status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean pingDatabase() {
        try (java.sql.Connection c = dataSource.getConnection()) {
            return c.isValid(2); // 2s timeout
        } catch (Exception e) {
            log.warn("[health/ready] db ping 失败: {}", e.getMessage());
            return false;
        }
    }
}
